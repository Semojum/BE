package com.semojum.backend.domain.job.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.job.scheduler.JobDispatcher;
import com.semojum.backend.domain.result.entity.*;
import com.semojum.backend.domain.result.repository.*;
import com.semojum.backend.domain.result.service.PageResultSerializer;
import com.semojum.backend.global.grpc.AiServerPool;
import com.semojum.backend.global.s3.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class SseService {

    private final JobRepository jobRepository;
    private final PageResultRepository pageResultRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final AiServerPool aiServerPool;
    private final PageResultSerializer pageResultSerializer;
    private final JobDispatcher jobDispatcher;
    private final PageRepository pageRepository;
    private final S3Service s3Service;

    // 페이지 조회 API와 같은 수명 — 변환 중 화면은 이벤트 도착 즉시 쓰므로 15분이면 충분하다
    private static final java.time.Duration ORIGINAL_URL_TTL = java.time.Duration.ofMinutes(15);

    private static final long EMITTER_TIMEOUT = 3 * 60 * 60 * 1000L; // 3시간 (대용량 문서 직렬 처리 대비 SSE 최대 수명)

    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    @PreDestroy
    public void shutdown() {
        sseExecutor.shutdown();
    }

    public SseEmitter connect(String jobId) {
        log.info("SSE 구독: jobId={}", jobId);
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT);
        // 멀티스레드 환경에서 루프 종료 신호를 안전하게 전달하기 위해 AtomicBoolean 사용
        AtomicBoolean running = new AtomicBoolean(true);

        // 클라이언트 연결 종료/타임아웃/에러 시 폴링 루프 중단
        emitter.onCompletion(() -> running.set(false));
        emitter.onTimeout(() -> running.set(false));
        emitter.onError(e -> running.set(false));

        // 폴링 루프를 별도 스레드에서 실행해 HTTP 요청 스레드를 블로킹하지 않음
        CompletableFuture.runAsync(() -> runPollingLoop(jobId, emitter, running), sseExecutor);

        return emitter;
    }

    private void runPollingLoop(String jobId, SseEmitter emitter, AtomicBoolean running) {
        // page_done 순서 보장 커서 — 이 번호까지 전송 완료. 병렬 변환이라 뒤 페이지가 먼저 끝날 수 있지만,
        // FE에는 반드시 1, 2, 3… 순서로 내보낸다(앞 페이지가 끝날 때까지 뒤 페이지 이벤트는 보류).
        int emittedUpTo = 0;

        while (running.get()) {
            try {
                Thread.sleep(1000);

                // SSE가 살아 있다 = 사용자가 보고 있다 → FG 리스(30s) 갱신.
                // 연결이 끊기면 루프가 멈춰 갱신이 중단되고, TTL 만료로 자연히 BG 강등된다.
                jobDispatcher.touchForeground(jobId);

                Map<Object, Object> redisData = redisTemplate.opsForHash().entries("job:" + jobId + ":pages");
                if (redisData.isEmpty()) continue;

                String totalPagesStr = (String) redisData.get("total_pages");
                if (totalPagesStr == null) continue;
                int totalPages = Integer.parseInt(totalPagesStr);

                int pendingCount = 0;
                int doneCount = 0;
                for (Map.Entry<Object, Object> entry : redisData.entrySet()) {
                    String key = (String) entry.getKey();
                    String value = (String) entry.getValue();
                    if (key.equals("total_pages")) continue;

                    switch (value) {
                        case "PENDING" -> pendingCount++;
                        case "COMPLETED", "NEEDS_REVIEW", "BLOCKED" -> doneCount++;
                    }
                }

                // 연속 완료 구간(1..K 전부 terminal) 끝까지 커서를 전진시키며 순서대로 전송.
                // 재연결 시에도 커서가 0부터 시작해 이미 완료된 페이지들을 순서대로 다시 내려준다(기존 동작 유지).
                int cursor = emittedUpTo;
                while (cursor < totalPages) {
                    String status = (String) redisData.get("page:" + (cursor + 1));
                    boolean isDone = "COMPLETED".equals(status) || "NEEDS_REVIEW".equals(status) || "BLOCKED".equals(status);
                    if (!isDone) break;
                    cursor++;
                }
                for (int pageNo = emittedUpTo + 1; pageNo <= cursor; pageNo++) {
                    sendPageDoneEvent(jobId, pageNo, (String) redisData.get("page:" + pageNo), emitter);
                }
                emittedUpTo = cursor;

                // queue_position 이벤트
                if (pendingCount > 0) {
                    Map<String, Object> queueEvent = new LinkedHashMap<>();
                    queueEvent.put("type", "queue_position");
                    queueEvent.put("position", pendingCount);
                    // 페이지당 약 30초 가정, 총 슬롯 수만큼 동시 처리되므로 슬롯 수로 나눈다
                    queueEvent.put("estimated_wait_sec", (int) Math.ceil(pendingCount * 30.0 / aiServerPool.getTotalSlots()));
                    emitter.send(SseEmitter.event().name("queue_position").data(objectMapper.writeValueAsString(queueEvent)));
                }

                // job_done 이벤트
                if (doneCount == totalPages) {
                    Job job = jobRepository.findById(jobId).orElse(null);
                    int[] failedPages = job != null && job.getFailedPages() != null ? job.getFailedPages() : new int[]{};

                    List<Integer> failedPagesList = new ArrayList<>();
                    for (int fp : failedPages) failedPagesList.add(fp);

                    Map<String, Object> jobDoneEvent = new LinkedHashMap<>();
                    jobDoneEvent.put("type", "job_done");
                    jobDoneEvent.put("job_id", jobId);
                    jobDoneEvent.put("total_pages", totalPages);
                    jobDoneEvent.put("failed_pages", failedPagesList);
                    emitter.send(SseEmitter.event().name("job_done").data(objectMapper.writeValueAsString(jobDoneEvent)));
                    emitter.complete();
                    running.set(false);
                    log.info("SSE 종료: jobId={} (job_done 전송, totalPages={}, failed={})",
                            jobId, totalPages, failedPagesList.size());
                }

            } catch (IOException e) {
                running.set(false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running.set(false);
            } catch (Exception e) {
                if (running.get()) {
                    log.error("SSE 폴링 루프 오류: jobId={}, {}", jobId, e.getMessage());
                }
            }
        }
    }

    // 페이지당 변환 결과 JSON 전문 로그 — 대형 작업에선 페이지당 10~30KB라 양이 크다.
    // 전용 로거로 분리해 필요 시 재빌드 없이 끌 수 있다:
    // EC2 .env에 LOGGING_LEVEL_SSE_PAYLOAD=OFF 추가 후 docker compose up -d
    private static final org.slf4j.Logger payloadLog = org.slf4j.LoggerFactory.getLogger("sse.payload");

    private void sendPageDoneEvent(String jobId, int pageNo, String status, SseEmitter emitter) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "page_done");
            event.put("job_id", jobId);
            event.put("page_no", pageNo);
            event.put("status", status);

            // ResultService가 DB 저장을 완료한 후 Redis 상태가 바뀌므로, 이 시점에 DB 조회 보장됨
            PageResult pageResult = pageResultRepository.findByJobIdAndPageNumber(jobId, pageNo).orElse(null);
            if (pageResult != null) {
                event.put("result", pageResultSerializer.buildResult(pageResult));
            }

            // 서버가 미리 렌더해 둔 원본 이미지(a·c). 변환 중 화면은 FE가 업로드한 로컬 파일을 pdf.js로
            // 그려 왔는데, 스캔본은 그 렌더가 쪽당 1.8~2.9초다(2026-08-31 실측). 이 URL을 쓰면 ~10ms.
            addOriginal(event, jobId, pageNo);

            String payload = objectMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event().name("page_done").data(payload));
            log.info("SSE page_done 방출: jobId={}, pageNo={}, status={}, payload={}B", jobId, pageNo, status, payload.length());
            payloadLog.info("jobId={}, pageNo={} :: {}", jobId, pageNo, payload);
        } catch (Exception e) {
            log.error("page_done 이벤트 전송 실패: jobId={}, pageNo={}, {}", jobId, pageNo, e.getMessage());
        }
    }

    /**
     * 미리 렌더해 둔 원본 이미지를 페이지 조회 API와 <b>같은 모양</b>({@code {type, url}})으로 싣는다 —
     * 같은 record를 그대로 써서 FE가 원본 렌더 코드를 한 벌만 두면 되게 한다.
     *
     * <p>이미지가 없으면(b · 렌더 실패 · page-image 비활성) <b>키 자체를 넣지 않는다.</b> 이때 PDF URL을
     * 대신 주면 FE가 이미 쥐고 있는 로컬 파일 대신 S3에서 굳이 내려받는 더 느린 길로 가게 된다 —
     * 변환 중 화면의 폴백은 그 로컬 파일이다(페이지 조회 API는 로컬 파일이 없어 pdf 폴백을 준다).
     *
     * <p>실패해도 이벤트 전송을 막지 않는다(로그만). (테스트 접근용 package-private)
     */
    void addOriginal(Map<String, Object> event, String jobId, int pageNo) {
        try {
            pageRepository.findByJob_IdAndPageNo(jobId, pageNo)
                    .map(page -> page.getImagePath())
                    .ifPresent(path -> event.put("original", new JobResponseDto.OriginalContent(
                            "image", s3Service.getPresignedUrl(path, ORIGINAL_URL_TTL), null)));
        } catch (Exception e) {
            log.warn("SSE 원본 이미지 URL 생성 실패(계속): jobId={}, pageNo={}, error={}", jobId, pageNo, e.getMessage());
        }
    }

    // 모드에 따라 FE에 전달할 result 필드 구성 (a: 텍스트추출, b: 점자변환, c: 이미지→점자)
}

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

    /**
     * 하트비트 간격 — 이만큼 아무것도 안 보냈으면 주석 줄(`: ping`)을 한 번 내보낸다.
     *
     * <p>보낼 이벤트가 없는 구간(마지막 쪽들이 전부 AI 서버에 들어가 있을 때, 최대 gRPC deadline
     * 400초)에는 전송이 아예 없어 <b>클라이언트가 사라져도 알 수 없었다</b>. 주기적으로 한 줄이라도
     * 내보내면 그때 IOException이 나면서 죽은 연결이 드러나 루프가 정리된다.
     *
     * <p>SSE 주석 줄은 클라이언트가 무시하는 규격이라 FE 계약에 영향이 없다.
     */
    static final long HEARTBEAT_INTERVAL_MS = 30_000L;

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
        // 마지막으로 무언가를 내보낸 시각 — 하트비트 판단 기준. 연결 직후를 기준점으로 잡는다.
        long lastSentAt = System.currentTimeMillis();

        while (running.get()) {
            try {
                Thread.sleep(1000);

                // SSE가 살아 있다 = 사용자가 보고 있다 → FG 리스(30s) 갱신.
                // 연결이 끊기면 루프가 멈춰 갱신이 중단되고, TTL 만료로 자연히 BG 강등된다.
                jobDispatcher.touchForeground(jobId);

                Map<Object, Object> redisData = redisTemplate.opsForHash().entries("job:" + jobId + ":pages");
                if (redisData.isEmpty()) {
                    // Hash가 없다 = "아직 기록 전"이거나 "끝나서 TTL(1h)로 지워졌다". 둘을 Redis만으로는
                    // 구분할 수 없어 종전엔 끝난 작업에 붙어도 이벤트 없이 3시간 매달렸다(하트비트는
                    // 전송이 정상 성공하므로 이 경우를 못 잡는다). 작업 상태는 DB에 남아 있으므로 확인한다.
                    Map<String, Object> jobDoneEvent = terminalJobDonePayload(jobId);
                    if (jobDoneEvent != null) {
                        emitter.send(SseEmitter.event().name("job_done").data(objectMapper.writeValueAsString(jobDoneEvent)));
                        emitter.complete();
                        running.set(false);
                        log.info("SSE 종료: jobId={} (이미 끝난 작업 — DB 상태로 job_done 전송)", jobId);
                        continue;
                    }
                    // 아직 진행 중(기록 전)이면 종전대로 기다린다
                    lastSentAt = maybeHeartbeat(emitter, lastSentAt, System.currentTimeMillis());
                    continue;
                }

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
                    if (sendPageDoneEvent(jobId, pageNo, (String) redisData.get("page:" + pageNo), emitter)) {
                        lastSentAt = System.currentTimeMillis();
                    }
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
                    lastSentAt = System.currentTimeMillis();
                }

                // job_done 이벤트
                if (doneCount == totalPages) {
                    Job job = jobRepository.findById(jobId).orElse(null);
                    int[] failedPages = job != null && job.getFailedPages() != null ? job.getFailedPages() : new int[]{};

                    Map<String, Object> jobDoneEvent = buildJobDonePayload(jobId, totalPages, failedPages);
                    emitter.send(SseEmitter.event().name("job_done").data(objectMapper.writeValueAsString(jobDoneEvent)));
                    emitter.complete();
                    running.set(false);
                    log.info("SSE 종료: jobId={} (job_done 전송, totalPages={}, failed={})",
                            jobId, totalPages, failedPages.length);
                    continue;
                }

                // 보낼 게 없는 구간이 길어지면 주석 줄로 연결 생사를 확인한다 (죽어 있으면 여기서 IOException)
                lastSentAt = maybeHeartbeat(emitter, lastSentAt, System.currentTimeMillis());

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

    /**
     * job_done 페이로드. 정상 종료와 "이미 끝난 작업" 두 경로가 <b>같은 모양</b>을 내보내야 해서
     * 한 곳에 모은다 — FE는 둘을 구분하지 않는다.
     */
    static Map<String, Object> buildJobDonePayload(String jobId, int totalPages, int[] failedPages) {
        List<Integer> failedPagesList = new ArrayList<>();
        if (failedPages != null) for (int fp : failedPages) failedPagesList.add(fp);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "job_done");
        event.put("job_id", jobId);
        event.put("total_pages", totalPages);
        event.put("failed_pages", failedPagesList);
        return event;
    }

    /**
     * Redis Hash가 비어 있을 때 <b>DB로</b> 종료 여부를 판단한다.
     *
     * <p>Hash는 작업 종료 시 TTL 1시간이 걸려 사라진다({@code ResultService}). 그 뒤에 붙은 연결은
     * "아직 기록 전"과 구분되지 않아 종전엔 무한히 기다렸다. 작업 상태는 {@code jobs}에 남아 있다.
     *
     * @return 종료 상태(COMPLETED·FAILED)면 job_done 페이로드, 아직 진행 중이면 null.
     *         작업이 DB에도 없으면 null (컨트롤러가 소유권을 검증하므로 정상 경로에선 오지 않는다)
     */
    Map<String, Object> terminalJobDonePayload(String jobId) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.isInProgress()) return null;
        return buildJobDonePayload(jobId, job.getTotalPages(), job.getFailedPages());
    }

    /**
     * 마지막 전송 후 {@link #HEARTBEAT_INTERVAL_MS}가 지났으면 주석 줄을 보낸다.
     *
     * @return 갱신된 "마지막 전송 시각" — 보냈으면 now, 아니면 받은 값 그대로
     * @throws IOException 연결이 죽어 있으면 여기서 터진다(루프의 catch가 정리한다)
     */
    long maybeHeartbeat(SseEmitter emitter, long lastSentAt, long now) throws IOException {
        if (now - lastSentAt < HEARTBEAT_INTERVAL_MS) return lastSentAt;
        emitter.send(SseEmitter.event().comment("ping"));
        return now;
    }

    // 페이지당 변환 결과 JSON 전문 로그 — 대형 작업에선 페이지당 10~30KB라 양이 크다.
    // 전용 로거로 분리해 필요 시 재빌드 없이 끌 수 있다:
    // EC2 .env에 LOGGING_LEVEL_SSE_PAYLOAD=OFF 추가 후 docker compose up -d
    private static final org.slf4j.Logger payloadLog = org.slf4j.LoggerFactory.getLogger("sse.payload");

    /** @return 실제로 내보냈으면 true (하트비트 타이머를 그때만 초기화한다) */
    private boolean sendPageDoneEvent(String jobId, int pageNo, String status, SseEmitter emitter) {
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
            addFooterBraille(event, jobId);

            String payload = objectMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event().name("page_done").data(payload));
            log.info("SSE page_done 방출: jobId={}, pageNo={}, status={}, payload={}B", jobId, pageNo, status, payload.length());
            payloadLog.info("jobId={}, pageNo={} :: {}", jobId, pageNo, payload);
            return true;
        } catch (Exception e) {
            log.error("page_done 이벤트 전송 실패: jobId={}, pageNo={}, {}", jobId, pageNo, e.getMessage());
            return false;
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
    /**
     * 이 면의 페이지행에 들어갈 점역된 꼬리말 (V31 · FE 요청 S-4).
     *
     * <p>업로드 때 미리 점역해 둔 값을 읽기만 한다 — 방출 경로에서 AI를 부르면 SSE가 그만큼 늦어진다.
     * 값이 없으면(꼬리말 미입력 · 점역 실패) <b>키를 넣지 않는다</b>. 페이지 조회 API는 그 자리에서
     * 다시 점역해 채우므로, 변환이 끝난 뒤 화면을 열면 결국 값이 온다.
     *
     * <p>실패해도 이벤트 전송을 막지 않는다(로그만). (테스트 접근용 package-private)
     */
    void addFooterBraille(Map<String, Object> event, String jobId) {
        try {
            jobRepository.findById(jobId)
                    .map(com.semojum.backend.domain.job.entity.Job::getFooterBraille)
                    .filter(f -> !f.isBlank())
                    .ifPresent(f -> event.put("footer_braille", f));
        } catch (Exception e) {
            log.warn("SSE 꼬리말 점역 조회 실패(계속): jobId={}, error={}", jobId, e.getMessage());
        }
    }

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

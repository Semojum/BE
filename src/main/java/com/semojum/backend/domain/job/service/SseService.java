package com.semojum.backend.domain.job.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.scheduler.JobDispatcher;
import com.semojum.backend.domain.result.entity.*;
import com.semojum.backend.domain.result.repository.*;
import com.semojum.backend.domain.result.service.PageResultSerializer;
import com.semojum.backend.global.grpc.AiServerPool;
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

    private static final long EMITTER_TIMEOUT = 3 * 60 * 60 * 1000L; // 3시간 (대용량 문서 직렬 처리 대비 SSE 최대 수명)

    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    @PreDestroy
    public void shutdown() {
        sseExecutor.shutdown();
    }

    public SseEmitter connect(String jobId) {
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
        Map<String, String> prevState = new HashMap<>();

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
                Map<String, String> currentState = new HashMap<>();

                for (Map.Entry<Object, Object> entry : redisData.entrySet()) {
                    String key = (String) entry.getKey();
                    String value = (String) entry.getValue();
                    if (key.equals("total_pages")) continue;
                    currentState.put(key, value);

                    switch (value) {
                        case "PENDING" -> pendingCount++;
                        case "COMPLETED", "NEEDS_REVIEW", "BLOCKED" -> doneCount++;
                    }
                }

                // 이전 상태와 비교해 PENDING/RUNNING → 완료 상태로 전환된 페이지만 이벤트 전송
                for (Map.Entry<String, String> entry : currentState.entrySet()) {
                    String key = entry.getKey();
                    String newStatus = entry.getValue();
                    // prevState에 없는 키는 최초 연결 시점에 이미 완료된 페이지이므로 PENDING으로 간주
                    String prevStatus = prevState.getOrDefault(key, "PENDING");

                    boolean wasPendingOrRunning = prevStatus.equals("PENDING") || prevStatus.equals("RUNNING");
                    boolean isDone = newStatus.equals("COMPLETED") || newStatus.equals("NEEDS_REVIEW") || newStatus.equals("BLOCKED");

                    if (wasPendingOrRunning && isDone) {
                        int pageNo = Integer.parseInt(key.replace("page:", ""));
                        sendPageDoneEvent(jobId, pageNo, newStatus, emitter);
                    }
                }

                // queue_position 이벤트
                if (pendingCount > 0) {
                    Map<String, Object> queueEvent = new LinkedHashMap<>();
                    queueEvent.put("type", "queue_position");
                    queueEvent.put("position", pendingCount);
                    // 페이지당 약 30초 가정, 총 슬롯 수만큼 동시 처리되므로 슬롯 수로 나눈다
                    queueEvent.put("estimated_wait_sec", (int) Math.ceil(pendingCount * 30.0 / aiServerPool.getTotalSlots()));
                    emitter.send(SseEmitter.event().name("queue_position").data(objectMapper.writeValueAsString(queueEvent)));
                }

                prevState = currentState;

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

            emitter.send(SseEmitter.event().name("page_done").data(objectMapper.writeValueAsString(event)));
        } catch (Exception e) {
            log.error("page_done 이벤트 전송 실패: jobId={}, pageNo={}, {}", jobId, pageNo, e.getMessage());
        }
    }

    // 모드에 따라 FE에 전달할 result 필드 구성 (a: 텍스트추출, b: 점자변환, c: 이미지→점자)
}

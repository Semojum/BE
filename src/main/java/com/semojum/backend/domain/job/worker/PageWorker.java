package com.semojum.backend.domain.job.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semojum.backend.domain.result.service.ResultService;
import com.semojum.backend.global.s3.S3Service;
import com.semojum.backend.global.grpc.BrailleGrpcClient;
import com.semojum.backend.grpc.BrailleRequest;
import com.semojum.backend.grpc.BrailleResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class PageWorker {

    private final RedisTemplate<String, String> redisTemplate;
    private final S3Service s3Service;
    private final BrailleGrpcClient grpcClient;
    private final ObjectMapper objectMapper;
    private final ResultService resultService;

    private static final String TASK_QUEUE = "task_queue";
    private static final int WORKER_COUNT = 1; // TODO: AI 서버가 병렬 처리 지원하면 다시 6으로 복구

    // 워커 실행 여부 플래그 (volatile: 멀티스레드 환경에서 즉시 반영)
    private volatile boolean running = true;
    private ExecutorService executor;

    // 애플리케이션 시작 시 워커 스레드 실행
    @PostConstruct
    public void startWorkers() {
        executor = Executors.newFixedThreadPool(WORKER_COUNT);
        for (int i = 0; i < WORKER_COUNT; i++) {
            final int workerId = i + 1;
            executor.submit(() -> runWorker(workerId));
        }
        log.info("PageWorker {}개 시작", WORKER_COUNT);
    }

    // 애플리케이션 종료 시 워커 스레드 정리
    @PreDestroy
    public void stopWorkers() {
        log.info("PageWorker 종료 중...");
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
        log.info("PageWorker 종료 완료");
    }

    private void runWorker(int workerId) {
        log.info("Worker-{} 시작", workerId);
        while (running) {
            String task = null;
            try {
                // task_queue에서 작업 꺼내기
                task = redisTemplate.opsForList().rightPop(TASK_QUEUE);
                if (task == null) {
                    Thread.sleep(100);
                    continue;
                }

                // 작업 파싱
                Map<String, Object> taskMap = objectMapper.readValue(task, Map.class);
                String jobId = (String) taskMap.get("jobId");
                int pageNo = (int) taskMap.get("pageNo");
                String gcsPath = (String) taskMap.get("gcsPath");
                String mode = (String) taskMap.get("mode");
                int totalPages = (int) taskMap.get("totalPages");

                log.info("Worker-{} 작업 시작: jobId={}, pageNo={}", workerId, jobId, pageNo);

                // Redis 상태 → RUNNING
                redisTemplate.opsForHash().put("job:" + jobId + ":pages", "page:" + pageNo, "RUNNING");

                // GCS에서 파일 다운로드
                byte[] fileData = s3Service.downloadFile(gcsPath);

                // gRPC 요청 빌드
                BrailleRequest.Builder requestBuilder = BrailleRequest.newBuilder()
                        .setJobId(jobId)
                        .setPageNo(pageNo)
                        .setTotalPages(totalPages)
                        .setMode(mode);

                if (mode.equals("b")) {
                    // mode b: 텍스트로 전송
                    String sourceText = new String(fileData);
                    requestBuilder.setSourceText(sourceText);
                } else {
                    // mode a, c: PDF 바이너리로 전송
                    requestBuilder.setPdfData(com.google.protobuf.ByteString.copyFrom(fileData));
                }

                // AI 서버에 gRPC 요청
                BrailleResponse response = grpcClient.processPage(requestBuilder.build());

                // DB에 결과 저장
                resultService.save(response);

                // Redis 상태 → COMPLETED or NEEDS_REVIEW or BLOCKED
                redisTemplate.opsForHash().put("job:" + jobId + ":pages", "page:" + pageNo, response.getStatus());

                log.info("Worker-{} 작업 완료: jobId={}, pageNo={}, status={}", workerId, jobId, pageNo, response.getStatus());

            } catch (Exception e) {
                if (running) {
                    if (task != null) {
                        try {
                            Map<String, Object> taskMap = new HashMap<>(objectMapper.readValue(task, Map.class));
                            String jobId = (String) taskMap.get("jobId");
                            int pageNo = (int) taskMap.get("pageNo");
                            int retryCount = taskMap.get("retryCount") != null ? (int) taskMap.get("retryCount") : 0;

                            if (retryCount < 3) {
                                // 재시도 횟수 증가 후 큐에 재등록
                                taskMap.put("retryCount", retryCount + 1);
                                redisTemplate.opsForHash().put("job:" + jobId + ":pages", "page:" + pageNo, "PENDING");
                                redisTemplate.opsForList().leftPush(TASK_QUEUE, objectMapper.writeValueAsString(taskMap));
                                log.error("Worker-{} 오류 발생, 재시도 큐에 등록 ({}/3): {}", workerId, retryCount + 1, e.getMessage());
                            } else {
                                // 최대 재시도 초과 → DB Page=BLOCKED + Job 종료 판정.
                                // DB 반영 실패와 무관하게 Redis는 항상 BLOCKED로 갱신(SSE가 종료를 감지하도록 보장).
                                try {
                                    resultService.markPageBlocked(jobId, pageNo);
                                } catch (Exception blockEx) {
                                    log.error("Worker-{} markPageBlocked 실패(Redis는 BLOCKED 유지): jobId={}, pageNo={}, error={}", workerId, jobId, pageNo, blockEx.getMessage(), blockEx);
                                }
                                redisTemplate.opsForHash().put("job:" + jobId + ":pages", "page:" + pageNo, "BLOCKED");
                                log.error("Worker-{} 최대 재시도 초과, BLOCKED 처리: jobId={}, pageNo={}, error={}", workerId, jobId, pageNo, e.getMessage());
                            }
                        } catch (Exception ex) {
                            // pop된 task가 무로그로 증발하지 않도록 가시화 (파싱 실패/leftPush 실패 등 포함).
                            // task JSON에 jobId/pageNo/retryCount가 들어 있다.
                            log.error("Worker-{} 예외 처리 중 추가 오류, task 처리 실패: task={}, error={}", workerId, task, ex.getMessage(), ex);
                        }
                    }
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {}
                }
            }
        }
        log.info("Worker-{} 종료", workerId);
    }
}

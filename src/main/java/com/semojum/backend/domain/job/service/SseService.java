package com.semojum.backend.domain.job.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.result.entity.*;
import com.semojum.backend.domain.result.repository.*;
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
    private final TextElementRepository textElementRepository;
    private final BrailleElementRepository brailleElementRepository;
    private final BoundingBoxRepository boundingBoxRepository;
    private final RuleTrailRepository ruleTrailRepository;
    private final QualityCriticalErrorRepository qualityCriticalErrorRepository;
    private final QualityReviewFlagRepository qualityReviewFlagRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final long EMITTER_TIMEOUT = 30 * 60 * 1000L;

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
                    queueEvent.put("estimated_wait_sec", pendingCount * 30);
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
                event.put("result", buildResult(pageResult));
            }

            emitter.send(SseEmitter.event().name("page_done").data(objectMapper.writeValueAsString(event)));
        } catch (Exception e) {
            log.error("page_done 이벤트 전송 실패: jobId={}, pageNo={}, {}", jobId, pageNo, e.getMessage());
        }
    }

    // 모드에 따라 FE에 전달할 result 필드 구성 (a: 텍스트추출, b: 점자변환, c: 이미지→점자)
    private Map<String, Object> buildResult(PageResult pageResult) {
        String mode = pageResult.getMode();
        Map<String, Object> result = new LinkedHashMap<>();

        List<TextElement> textElements = textElementRepository.findByPageResult(pageResult);
        List<BrailleElement> brailleElements = brailleElementRepository.findByPageResult(pageResult);
        List<BoundingBox> boundingBoxes = boundingBoxRepository.findByPageResult(pageResult);
        List<QualityCriticalError> criticalErrors = qualityCriticalErrorRepository.findByPageResult(pageResult);
        List<QualityReviewFlag> reviewFlags = qualityReviewFlagRepository.findByPageResult(pageResult);

        switch (mode) {
            case "a" -> {
                if (pageResult.getImageWidth() != null) {
                    Map<String, Object> imgRes = new LinkedHashMap<>();
                    imgRes.put("width", pageResult.getImageWidth());
                    imgRes.put("height", pageResult.getImageHeight());
                    result.put("image_resolution", imgRes);
                }
                result.put("bounding_box_list", buildBoundingBoxList(boundingBoxes));
                result.put("text_list", buildTextListFull(textElements));
                result.put("quality_report", buildQualityReport(pageResult, criticalErrors, reviewFlags));
            }
            case "b" -> {
                result.put("text_list", buildTextListSimple(textElements));
                result.put("braille_text_list", buildBrailleListFull(brailleElements));
                result.put("quality_report", buildQualityReport(pageResult, criticalErrors, reviewFlags));
            }
            case "c" -> {
                if (pageResult.getImageWidth() != null) {
                    Map<String, Object> imgRes = new LinkedHashMap<>();
                    imgRes.put("width", pageResult.getImageWidth());
                    imgRes.put("height", pageResult.getImageHeight());
                    result.put("image_resolution", imgRes);
                }
                result.put("bounding_box_list", buildBoundingBoxList(boundingBoxes));
                result.put("braille_text_list", buildBrailleListFull(brailleElements));
                result.put("quality_report", buildQualityReport(pageResult, criticalErrors, reviewFlags));
            }
        }

        return result;
    }

    private List<Map<String, Object>> buildTextListFull(List<TextElement> elements) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TextElement el : elements) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", el.getElementId());
            map.put("type", el.getType());
            map.put("order", el.getReadingOrder());
            map.put("heading_level", el.getHeadingLevel());
            map.put("tn_text", el.getTnText());
            map.put("latex_string", el.getLatexString());
            map.put("selected_idx", el.getSelectedIdx());
            map.put("render_mode", el.getRenderMode());
            map.put("visual_subtype", el.getVisualSubtype());
            map.put("contents", el.getCurrentContents());
            map.put("drafts", el.getDrafts());
            map.put("is_blocked", el.isBlocked());
            map.put("rule_trail", buildRuleTrailList(el.getId()));
            list.add(map);
        }
        return list;
    }

    private List<Map<String, Object>> buildTextListSimple(List<TextElement> elements) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TextElement el : elements) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", el.getElementId());
            map.put("contents", el.getCurrentContents());
            list.add(map);
        }
        return list;
    }

    private List<Map<String, Object>> buildBrailleListFull(List<BrailleElement> elements) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (BrailleElement el : elements) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", el.getElementId());
            map.put("type", el.getType());
            map.put("order", el.getReadingOrder());
            map.put("heading_level", el.getHeadingLevel());
            map.put("tn_text", el.getTnText());
            map.put("latex_string", el.getLatexString());
            map.put("selected_idx", el.getSelectedIdx());
            map.put("render_mode", el.getRenderMode());
            map.put("visual_subtype", el.getVisualSubtype());
            map.put("contents", el.getCurrentContent());
            map.put("drafts", el.getDrafts());
            map.put("is_blocked", el.isBlocked());
            map.put("rule_trail", buildRuleTrailList(el.getId()));
            list.add(map);
        }
        return list;
    }

    private List<Map<String, Object>> buildBoundingBoxList(List<BoundingBox> boxes) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (BoundingBox box : boxes) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", box.getElementId());
            map.put("x", box.getX());
            map.put("y", box.getY());
            map.put("x2", box.getX2());
            map.put("y2", box.getY2());
            map.put("type", box.getType());
            map.put("heading_level", box.getHeadingLevel());
            map.put("caption_ref", box.getCaptionRef());
            map.put("flags", box.getFlags());
            list.add(map);
        }
        return list;
    }

    private List<Map<String, Object>> buildRuleTrailList(UUID elementId) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (RuleTrail rt : ruleTrailRepository.findByElementId(elementId)) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("rule_id", rt.getRuleId());
            map.put("source", rt.getSource());
            map.put("priority", rt.getPriority());
            map.put("section", rt.getSection());
            map.put("title", rt.getTitle());
            map.put("excerpt", rt.getExcerpt());
            map.put("span_start", rt.getSpanStart());
            map.put("span_end", rt.getSpanEnd());
            map.put("tag", rt.getTag());
            list.add(map);
        }
        return list;
    }

    private Map<String, Object> buildQualityReport(PageResult pageResult,
                                                    List<QualityCriticalError> criticalErrors,
                                                    List<QualityReviewFlag> reviewFlags) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ocr_confidence_avg", pageResult.getOcrConfidenceAvg());
        report.put("line_overflow_rate", pageResult.getLineOverflowRate());

        List<Map<String, Object>> errors = new ArrayList<>();
        for (QualityCriticalError e : criticalErrors) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", e.getType());
            m.put("element_id", e.getElementId());
            m.put("message", e.getMessage());
            errors.add(m);
        }
        report.put("critical_errors", errors);

        List<Map<String, Object>> flags = new ArrayList<>();
        for (QualityReviewFlag f : reviewFlags) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", f.getType());
            m.put("element_id", f.getElementId());
            m.put("message", f.getMessage());
            flags.add(m);
        }
        report.put("review_flags", flags);

        return report;
    }
}

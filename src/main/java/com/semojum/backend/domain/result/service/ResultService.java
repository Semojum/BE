package com.semojum.backend.domain.result.service;

import com.google.protobuf.util.JsonFormat;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.result.entity.*;
import com.semojum.backend.domain.result.repository.*;
import com.semojum.backend.grpc.BrailleResponse;
import com.semojum.backend.grpc.ProcessingMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResultService {

    private final JobRepository jobRepository;
    private final PageRepository pageRepository;
    private final PageResultRepository pageResultRepository;
    private final TextElementRepository textElementRepository;
    private final BrailleElementRepository brailleElementRepository;
    private final BoundingBoxRepository boundingBoxRepository;
    private final RuleTrailRepository ruleTrailRepository;
    private final QualityCriticalErrorRepository qualityCriticalErrorRepository;
    private final QualityReviewFlagRepository qualityReviewFlagRepository;
    private final RedisTemplate<String, String> redisTemplate;

    @Transactional
    public void save(BrailleResponse response) {
        String jobId = response.getJobId();
        int pageNumber = response.getPageNumber();
        String status = response.getStatus();

        // Job, Page 조회
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));
        Page page = pageRepository.findByJobAndPageNo(job, pageNumber)
                .orElseThrow(() -> new RuntimeException("Page not found: " + jobId + ", pageNo=" + pageNumber));

        // raw_response 직렬화
        String rawResponse;
        try {
            rawResponse = JsonFormat.printer().print(response);
        } catch (Exception e) {
            rawResponse = "{}";
            log.warn("BrailleResponse JSON 직렬화 실패: {}", e.getMessage());
        }

        // ProcessingMeta 파싱
        Integer processingTimeMs = null;
        Double pdfLayerConfidence = null;
        String routingTierUsed = null;
        Boolean scanOnly = null;
        if (response.hasProcessingMeta()) {
            ProcessingMeta meta = response.getProcessingMeta();
            processingTimeMs = meta.getProcessingTimeMs() > 0 ? meta.getProcessingTimeMs() : null;
            pdfLayerConfidence = meta.getPdfLayerConfidence() > 0 ? (double) meta.getPdfLayerConfidence() : null;
            routingTierUsed = meta.getRoutingTierUsed().isEmpty() ? null : meta.getRoutingTierUsed();
            scanOnly = meta.getScanOnly();
        }

        // PageResult 저장
        PageResult pageResult = PageResult.builder()
                .job(job)
                .page(page)
                .pageNumber(pageNumber)
                .mode(job.getMode())
                .status(status)
                .imageWidth(response.getImageWidth() > 0 ? response.getImageWidth() : null)
                .imageHeight(response.getImageHeight() > 0 ? response.getImageHeight() : null)
                .ocrConfidenceAvg(response.hasQualityReport() ? (double) response.getQualityReport().getOcrConfidenceAvg() : null)
                .lineOverflowRate(response.hasQualityReport() ? (double) response.getQualityReport().getLineOverflowRate() : null)
                .processingTimeMs(processingTimeMs)
                .pdfLayerConfidence(pdfLayerConfidence)
                .routingTierUsed(routingTierUsed)
                .scanOnly(scanOnly)
                .rawResponse(rawResponse)
                .build();
        pageResultRepository.save(pageResult);

        // TextElement 저장 (text_list)
        for (com.semojum.backend.grpc.TextElement protoText : response.getTextListList()) {
            List<Map<String, Object>> draftsJson = serializeDrafts(protoText);
            TextElement textElement = TextElement.builder()
                    .pageResult(pageResult)
                    .elementId(protoText.getId())
                    .type(protoText.getType().isEmpty() ? null : protoText.getType())
                    .readingOrder(protoText.getOrder() > 0 ? protoText.getOrder() : null)
                    .headingLevel(protoText.getHeadingLevel() > 0 ? protoText.getHeadingLevel() : null)
                    .ocrConfidence(protoText.getOcrConfidence() > 0 ? (double) protoText.getOcrConfidence() : null)
                    .tnText(protoText.getTnText().isEmpty() ? null : protoText.getTnText())
                    .latexString(protoText.getLatexString().isEmpty() ? null : protoText.getLatexString())
                    .selectedIdx(protoText.getSelectedIdx())
                    .renderMode(protoText.getRenderMode().isEmpty() ? null : protoText.getRenderMode())
                    .visualSubtype(protoText.getVisualSubtype().isEmpty() ? null : protoText.getVisualSubtype())
                    .subtypeConfidence(protoText.getSubtypeConfidence() > 0 ? (double) protoText.getSubtypeConfidence() : null)
                    .contents(new ArrayList<>(protoText.getContentsList()))
                    .drafts(draftsJson)
                    .isBlocked(protoText.getIsBlocked())
                    .build();
            textElementRepository.save(textElement);

            // RuleTrail 저장
            for (com.semojum.backend.grpc.RuleTrail protoRule : protoText.getRuleTrailList()) {
                ruleTrailRepository.save(buildRuleTrail(protoRule, textElement.getId(), "TEXT"));
            }
        }

        // BrailleElement 저장 (braille_text_list)
        for (com.semojum.backend.grpc.TextElement protoBraille : response.getBrailleTextListList()) {
            List<Map<String, Object>> draftsJson = serializeDrafts(protoBraille);
            BrailleElement brailleElement = BrailleElement.builder()
                    .pageResult(pageResult)
                    .elementId(protoBraille.getId())
                    .type(protoBraille.getType().isEmpty() ? "text" : protoBraille.getType())
                    .readingOrder(protoBraille.getOrder() > 0 ? protoBraille.getOrder() : null)
                    .headingLevel(protoBraille.getHeadingLevel() > 0 ? protoBraille.getHeadingLevel() : null)
                    .ocrConfidence(protoBraille.getOcrConfidence() > 0 ? (double) protoBraille.getOcrConfidence() : null)
                    .tnText(protoBraille.getTnText().isEmpty() ? null : protoBraille.getTnText())
                    .latexString(protoBraille.getLatexString().isEmpty() ? null : protoBraille.getLatexString())
                    .selectedIdx(protoBraille.getSelectedIdx())
                    .renderMode(protoBraille.getRenderMode().isEmpty() ? null : protoBraille.getRenderMode())
                    .visualSubtype(protoBraille.getVisualSubtype().isEmpty() ? null : protoBraille.getVisualSubtype())
                    .subtypeConfidence(protoBraille.getSubtypeConfidence() > 0 ? (double) protoBraille.getSubtypeConfidence() : null)
                    .content(new ArrayList<>(protoBraille.getContentsList()))
                    .drafts(draftsJson)
                    .isBlocked(protoBraille.getIsBlocked())
                    .build();
            brailleElementRepository.save(brailleElement);

            // RuleTrail 저장
            for (com.semojum.backend.grpc.RuleTrail protoRule : protoBraille.getRuleTrailList()) {
                ruleTrailRepository.save(buildRuleTrail(protoRule, brailleElement.getId(), "BRAILLE"));
            }
        }

        // BoundingBox 저장 (mode a, c)
        for (com.semojum.backend.grpc.BoundingBox protoBbox : response.getBoundingBoxListList()) {
            BoundingBox boundingBox = BoundingBox.builder()
                    .pageResult(pageResult)
                    .elementId(protoBbox.getId())
                    .x(protoBbox.getX())
                    .y(protoBbox.getY())
                    .x2(protoBbox.getX2())
                    .y2(protoBbox.getY2())
                    .type(protoBbox.getType().isEmpty() ? null : protoBbox.getType())
                    .headingLevel(protoBbox.getHeadingLevel() > 0 ? protoBbox.getHeadingLevel() : null)
                    .captionRef(protoBbox.getCaptionRef().isEmpty() ? null : protoBbox.getCaptionRef())
                    .flags(protoBbox.getFlagsList().isEmpty() ? null : new ArrayList<>(protoBbox.getFlagsList()))
                    .build();
            boundingBoxRepository.save(boundingBox);
        }

        // QualityCriticalError 저장
        if (response.hasQualityReport()) {
            for (com.semojum.backend.grpc.CriticalError protoError : response.getQualityReport().getCriticalErrorsList()) {
                QualityCriticalError error = QualityCriticalError.builder()
                        .pageResult(pageResult)
                        .type(protoError.getType())
                        .elementId(protoError.getElementId())
                        .message(protoError.getMessage())
                        .build();
                qualityCriticalErrorRepository.save(error);
            }

            // QualityReviewFlag 저장
            for (com.semojum.backend.grpc.ReviewFlag protoFlag : response.getQualityReport().getReviewFlagsList()) {
                QualityReviewFlag flag = QualityReviewFlag.builder()
                        .pageResult(pageResult)
                        .type(protoFlag.getType())
                        .elementId(protoFlag.getElementId())
                        .message(protoFlag.getMessage())
                        .build();
                qualityReviewFlagRepository.save(flag);
            }
        }

        // Page 상태 업데이트
        page.updateStatus(status);
        pageRepository.save(page);

        // PENDING→IN_PROGRESS 전이 + updated_at 갱신 (이미 종료된 Job은 가드로 보호)
        jobRepository.touchJob(jobId);

        // 종료 판정 (BLOCKED 경로와 공유)
        evaluateJobTermination(job, jobId);

        log.info("ResultService 저장 완료: jobId={}, pageNo={}, status={}", jobId, pageNumber, status);
    }

    // 페이지가 최대 재시도 초과로 BLOCKED 될 때 DB에 즉시 반영하는 경로 (PageWorker에서 호출).
    // self-invocation 무력화를 피하기 위해 PageWorker가 이 빈의 public 메서드를 직접 호출한다.
    @Transactional
    public void markPageBlocked(String jobId, int pageNo) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));
        Page page = pageRepository.findByJobAndPageNo(job, pageNo)
                .orElseThrow(() -> new RuntimeException("Page not found: " + jobId + ", pageNo=" + pageNo));

        // DB Page 행을 BLOCKED로 저장
        page.updateStatus("BLOCKED");
        pageRepository.save(page);

        // updated_at 갱신 + 종료 판정 (성공 경로와 동일)
        jobRepository.touchJob(jobId);
        evaluateJobTermination(job, jobId);

        log.info("Page BLOCKED 처리: jobId={}, pageNo={}", jobId, pageNo);
    }

    // 성공 경로와 BLOCKED 경로가 공유하는 Job 종료 판정.
    // 모든 페이지가 terminal(COMPLETED/NEEDS_REVIEW/BLOCKED)일 때만 종료하며,
    // 성공(COMPLETED/NEEDS_REVIEW)이 하나도 없고 전부 BLOCKED면 FAILED, 하나라도 성공이면 COMPLETED.
    private void evaluateJobTermination(Job job, String jobId) {
        long terminalCount = pageRepository.countByJobAndStatusIn(job,
                List.of("COMPLETED", "NEEDS_REVIEW", "BLOCKED"));
        if (terminalCount != job.getTotalPages()) {
            return;
        }

        long successCount = pageRepository.countByJobAndStatusIn(job,
                List.of("COMPLETED", "NEEDS_REVIEW"));
        List<Page> blockedPages = pageRepository.findByJobAndStatus(job, "BLOCKED");
        int[] failedPageNos = blockedPages.stream()
                .mapToInt(Page::getPageNo)
                .toArray();

        String newStatus = successCount == 0 ? "FAILED" : "COMPLETED";
        jobRepository.finishJob(jobId, newStatus, toPgIntArray(failedPageNos));
        // Job 전체 종료 확정 → 페이지 상태 Hash에 TTL 1시간 부여 (완료 후 자동 삭제, Redis 메모리 누수 방지)
        redisTemplate.expire("job:" + jobId + ":pages", Duration.ofHours(1));
        log.info("Job 종료 판정: jobId={}, status={}, failedPages={}", jobId, newStatus, failedPageNos.length);
    }

    // int[] → PostgreSQL integer[] 텍스트 리터럴('{1,2,3}'). 값은 페이지 번호(정수)라 인젝션 위험 없음.
    private String toPgIntArray(int[] nums) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < nums.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(nums[i]);
        }
        return sb.append("}").toString();
    }

    // RuleTrail 빌드 헬퍼
    private RuleTrail buildRuleTrail(com.semojum.backend.grpc.RuleTrail protoRule, UUID elementId, String elementType) {
        return RuleTrail.builder()
                .elementId(elementId)
                .elementType(elementType)
                .ruleId(protoRule.getRuleId())
                .source(protoRule.getSource().isEmpty() ? null : protoRule.getSource())
                .priority(protoRule.getPriority().isEmpty() ? null : protoRule.getPriority())
                .section(protoRule.getSection())
                .title(protoRule.getTitle())
                .excerpt(protoRule.getExcerpt())
                .lineNo(protoRule.getLineNo())
                .colStart(protoRule.getColStart() >= 0 ? protoRule.getColStart() : null)
                .colEnd(protoRule.getColEnd() >= 0 ? protoRule.getColEnd() : null)
                .tag(protoRule.getTag().isEmpty() ? null : protoRule.getTag())
                .build();
    }

    // Drafts 변환 헬퍼: proto Draft(text/contents/label) → List<Map>.
    // jsonb 배열로 저장되고 조회 시 그대로 배열로 응답됨(문자열 이중 인코딩 방지).
    private List<Map<String, Object>> serializeDrafts(com.semojum.backend.grpc.TextElement protoText) {
        if (protoText.getDraftsList().isEmpty()) return null;
        List<Map<String, Object>> drafts = new ArrayList<>();
        for (com.semojum.backend.grpc.Draft draft : protoText.getDraftsList()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("text", draft.getText());
            map.put("contents", new ArrayList<>(draft.getContentsList()));
            map.put("label", draft.getLabel());
            drafts.add(map);
        }
        return drafts;
    }


}

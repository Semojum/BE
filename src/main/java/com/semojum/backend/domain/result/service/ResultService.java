package com.semojum.backend.domain.result.service;

import com.google.protobuf.util.JsonFormat;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.result.entity.*;
import com.semojum.backend.domain.result.repository.*;
import com.semojum.backend.grpc.BrailleResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

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
                .rawResponse(rawResponse)
                .build();
        pageResultRepository.save(pageResult);

        // TextElement 저장 (text_list)
        for (com.semojum.backend.grpc.TextElement protoText : response.getTextListList()) {
            TextElement textElement = TextElement.builder()
                    .pageResult(pageResult)
                    .elementId(parseId(protoText.getId()))
                    .type(protoText.getType().isEmpty() ? null : protoText.getType())
                    .readingOrder(protoText.getOrder() > 0 ? protoText.getOrder() : null)
                    .contents(new ArrayList<>(protoText.getContentsList()))
                    .isBlocked(protoText.getIsBlocked())
                    .build();
            textElementRepository.save(textElement);

            // RuleTrail 저장
            for (com.semojum.backend.grpc.RuleTrail protoRule : protoText.getRuleTrailList()) {
                RuleTrail ruleTrail = RuleTrail.builder()
                        .elementId(textElement.getId())
                        .elementType("TEXT")
                        .ruleId(protoRule.getRuleId())
                        .section(protoRule.getSection())
                        .title(protoRule.getTitle())
                        .excerpt(protoRule.getExcerpt())
                        .build();
                ruleTrailRepository.save(ruleTrail);
            }
        }

        // BrailleElement 저장 (braille_text_list)
        for (com.semojum.backend.grpc.TextElement protoBraille : response.getBrailleTextListList()) {
            BrailleElement brailleElement = BrailleElement.builder()
                    .pageResult(pageResult)
                    .elementId(parseId(protoBraille.getId()))
                    .type(protoBraille.getType().isEmpty() ? "text" : protoBraille.getType())
                    .content(new ArrayList<>(protoBraille.getContentsList()))
                    .isBlocked(protoBraille.getIsBlocked())
                    .build();
            brailleElementRepository.save(brailleElement);

            // RuleTrail 저장
            for (com.semojum.backend.grpc.RuleTrail protoRule : protoBraille.getRuleTrailList()) {
                RuleTrail ruleTrail = RuleTrail.builder()
                        .elementId(brailleElement.getId())
                        .elementType("BRAILLE")
                        .ruleId(protoRule.getRuleId())
                        .section(protoRule.getSection())
                        .title(protoRule.getTitle())
                        .excerpt(protoRule.getExcerpt())
                        .build();
                ruleTrailRepository.save(ruleTrail);
            }
        }

        // BoundingBox 저장 (mode a, c)
        for (com.semojum.backend.grpc.BoundingBox protoBbox : response.getBoundingBoxListList()) {
            BoundingBox boundingBox = BoundingBox.builder()
                    .pageResult(pageResult)
                    .elementId(parseId(protoBbox.getId()))
                    .x(protoBbox.getX())
                    .y(protoBbox.getY())
                    .x2(protoBbox.getX2())
                    .y2(protoBbox.getY2())
                    .build();
            boundingBoxRepository.save(boundingBox);
        }

        // QualityCriticalError 저장
        if (response.hasQualityReport()) {
            for (com.semojum.backend.grpc.CriticalError protoError : response.getQualityReport().getCriticalErrorsList()) {
                QualityCriticalError error = QualityCriticalError.builder()
                        .pageResult(pageResult)
                        .type(protoError.getType())
                        .elementId(parseId(protoError.getElementId()))
                        .message(protoError.getMessage())
                        .build();
                qualityCriticalErrorRepository.save(error);
            }

            // QualityReviewFlag 저장
            for (com.semojum.backend.grpc.ReviewFlag protoFlag : response.getQualityReport().getReviewFlagsList()) {
                QualityReviewFlag flag = QualityReviewFlag.builder()
                        .pageResult(pageResult)
                        .type(protoFlag.getType())
                        .elementId(parseId(protoFlag.getElementId()))
                        .message(protoFlag.getMessage())
                        .build();
                qualityReviewFlagRepository.save(flag);
            }
        }

        // Page 상태 업데이트
        page.updateStatus(status);
        pageRepository.save(page);

        // Job 완료 여부 확인
        long doneCount = pageRepository.countByJobAndStatusIn(job,
                List.of("COMPLETED", "NEEDS_REVIEW", "BLOCKED"));
        if (doneCount == job.getTotalPages()) {
            List<Page> blockedPages = pageRepository.findByJobAndStatus(job, "BLOCKED");
            int[] failedPageNos = blockedPages.stream()
                    .mapToInt(Page::getPageNo)
                    .toArray();
            job.complete(failedPageNos);
            jobRepository.save(job);
            log.info("Job 완료: jobId={}, failedPages={}", jobId, failedPageNos.length);
        } else {
            job.updateStatus("IN_PROGRESS");
            jobRepository.save(job);
        }

        log.info("ResultService 저장 완료: jobId={}, pageNo={}, status={}", jobId, pageNumber, status);
    }

    private int parseId(String id) {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

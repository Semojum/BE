package com.semojum.backend.domain.user.service;

import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.result.entity.*;
import com.semojum.backend.domain.result.repository.*;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.s3.S3Service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@RequiredArgsConstructor
public class UserService {

    private final JobRepository jobRepository;
    private final PageRepository pageRepository;
    private final S3Service s3Service;
    private final PageResultRepository pageResultRepository;
    private final TextElementRepository textElementRepository;
    private final BrailleElementRepository brailleElementRepository;
    private final BoundingBoxRepository boundingBoxRepository;
    private final RuleTrailRepository ruleTrailRepository;
    private final QualityCriticalErrorRepository qualityCriticalErrorRepository;
    private final QualityReviewFlagRepository qualityReviewFlagRepository;

    @Transactional(readOnly = true)
    public List<JobResponseDto.JobSummary> getMyJobs(String userId) {
        List<Job> jobs = jobRepository.findByUserIdOrderByStartedAtDesc(UUID.fromString(userId));
        List<JobResponseDto.JobSummary> result = new ArrayList<>();
        for (Job job : jobs) {
            result.add(new JobResponseDto.JobSummary(
                    job.getId(),
                    job.getMode(),
                    job.getStatus(),
                    job.getTotalPages(),
                    job.getFailedPages(),
                    job.getOriginalFileName(),
                    job.getThumbnailUrl(),
                    job.getStartedAt(),
                    job.getFinishedAt()
            ));
        }
        return result;
    }

    // 앱 재시작·네트워크 재연결 시 복구용: 아직 진행 중인 Job 목록.
    // FE는 이 목록으로 각 Job의 status를 조회하고 SSE를 다시 연결한다(탭 2개 이상 대응).
    @Transactional(readOnly = true)
    public List<JobResponseDto.JobSummary> getActiveJobs(String userId) {
        List<Job> jobs = jobRepository.findByUserIdAndStatusInOrderByStartedAtDesc(
                UUID.fromString(userId), List.of("PENDING", "IN_PROGRESS"));
        List<JobResponseDto.JobSummary> result = new ArrayList<>();
        for (Job job : jobs) {
            result.add(new JobResponseDto.JobSummary(
                    job.getId(),
                    job.getMode(),
                    job.getStatus(),
                    job.getTotalPages(),
                    job.getFailedPages(),
                    job.getOriginalFileName(),
                    job.getThumbnailUrl(),
                    job.getStartedAt(),
                    job.getFinishedAt()
            ));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public JobResponseDto.JobDetail getJobPage(String userId, String jobId, int pageNo) {
        // 본인 Job인지 검증 (타인 jobId 직접 입력 시 403 반환)
        Job job = jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_FORBIDDEN));

        PageResult pageResult = pageResultRepository.findByJobIdAndPageNumber(jobId, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));

        // 페이지별 원본 정보 구성 (a/c: 원본 PDF 공개 URL, b: 원본 텍스트 줄 배열)
        Page page = pageRepository.findByJobAndPageNo(job, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        JobResponseDto.OriginalContent original = buildOriginal(job.getMode(), page);

        return new JobResponseDto.JobDetail(
                jobId,
                job.getMode(),
                job.getStatus(),
                job.getTotalPages(),
                job.getFailedPages(),
                job.getOriginalFileName(),
                job.getStartedAt(),
                job.getFinishedAt(),
                pageNo,
                buildResult(pageResult),
                original
        );
    }

    // 모드별 원본 구성. pdfPath(gs:// 전체경로)를 가공 없이 그대로 GcsService에 넘긴다.
    private JobResponseDto.OriginalContent buildOriginal(String mode, Page page) {
        if ("b".equals(mode)) {
            // mode b: GCS의 .txt를 읽어 줄 단위 배열로. split("\n", -1)로 빈 줄 보존(trim/필터 금지).
            String text = new String(s3Service.downloadFile(page.getPdfPath()), StandardCharsets.UTF_8);
            List<String> lines = Arrays.asList(text.split("\n", -1));
            return new JobResponseDto.OriginalContent("text", null, lines);
        }
        // mode a, c: 원본 PDF 공개 URL
        String url = s3Service.getPublicUrl(page.getPdfPath());
        return new JobResponseDto.OriginalContent("pdf", url, null);
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
            map.put("line_no", rt.getLineNo());
            map.put("col_start", rt.getColStart());
            map.put("col_end", rt.getColEnd());
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

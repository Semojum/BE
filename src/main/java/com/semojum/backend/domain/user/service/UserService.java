package com.semojum.backend.domain.user.service;

import com.semojum.backend.domain.folder.entity.Folder;
import com.semojum.backend.domain.folder.repository.FolderRepository;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.dto.JobSearchCondition;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobQueryRepositoryImpl;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.result.entity.*;
import com.semojum.backend.domain.result.repository.*;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.s3.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final JobRepository jobRepository;
    private final PageRepository pageRepository;
    private final FolderRepository folderRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final S3Service s3Service;
    private final PageResultRepository pageResultRepository;
    private final TextElementRepository textElementRepository;
    private final BrailleElementRepository brailleElementRepository;
    private final BoundingBoxRepository boundingBoxRepository;
    private final RuleTrailRepository ruleTrailRepository;
    private final QualityCriticalErrorRepository qualityCriticalErrorRepository;
    private final QualityReviewFlagRepository qualityReviewFlagRepository;

    /**
     * 마이페이지 작업 목록 — 폴더 범위·검색·필터·정렬·커서 페이지네이션.
     *
     * <p>휴지통 항목은 조건에서 제외되며, 진행률은 <b>진행 중인 작업만</b> Redis에서 읽어
     * 목록 크기만큼 조회가 늘어나지 않게 한다.
     */
    @Transactional(readOnly = true)
    public JobResponseDto.JobList getMyJobs(String userId, JobSearchCondition condition) {
        UUID uid = UUID.fromString(userId);
        List<Job> fetched = jobRepository.search(uid, condition);
        JobQueryRepositoryImpl.PageSlice slice =
                JobQueryRepositoryImpl.slice(fetched, condition.normalizedSize());

        Map<UUID, String> folderPaths = buildFolderPaths(uid, slice.items());

        List<JobResponseDto.JobCard> cards = new ArrayList<>();
        for (Job job : slice.items()) {
            cards.add(toCard(job, folderPaths));
        }
        return new JobResponseDto.JobList(cards, slice.nextCursor(), slice.hasMore());
    }

    private JobResponseDto.JobCard toCard(Job job, Map<UUID, String> folderPaths) {
        return new JobResponseDto.JobCard(
                job.getId(),
                job.getMode(),
                job.getStatus(),
                job.getTotalPages(),
                job.getFailedPages(),
                job.getOriginalFileName(),
                job.getThumbnailUrl(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getLastModifiedAt(),
                job.getLastEditedPage(),
                job.isEdited(),
                job.isFavorite(),
                job.isInsertPageNumber(),
                progressOf(job),
                job.getFolderId() != null ? job.getFolderId().toString() : null,
                job.getFolderId() != null ? folderPaths.get(job.getFolderId()) : null
        );
    }

    /** 진행 중 작업의 완료 페이지 비율(0~100). 그 외에는 null. */
    private Integer progressOf(Job job) {
        if (!job.isInProgress()) return null;
        try {
            Map<Object, Object> pages = redisTemplate.opsForHash().entries("job:" + job.getId() + ":pages");
            if (pages.isEmpty()) return 0;
            int done = 0, total = 0;
            for (Map.Entry<Object, Object> e : pages.entrySet()) {
                if ("total_pages".equals(e.getKey())) continue;
                total++;
                if (Set.of("COMPLETED", "NEEDS_REVIEW", "BLOCKED").contains(String.valueOf(e.getValue()))) done++;
            }
            return total == 0 ? 0 : (int) Math.round(done * 100.0 / total);
        } catch (Exception e) {
            // 진행률은 부가 정보 — Redis 장애가 목록 조회 전체를 막지 않게 한다
            log.warn("진행률 조회 실패: jobId={}", job.getId(), e);
            return null;
        }
    }

    /** 카드에 표시할 폴더 경로("상위/하위"). 계정당 폴더 200개 상한이라 한 번에 읽어 메모리에서 계산. */
    private Map<UUID, String> buildFolderPaths(UUID userId, List<Job> jobs) {
        boolean anyInFolder = jobs.stream().anyMatch(j -> j.getFolderId() != null);
        if (!anyInFolder) return Map.of();

        Map<UUID, Folder> byId = new HashMap<>();
        for (Folder f : folderRepository.findAllActiveByUserId(userId)) {
            byId.put(f.getId(), f);
        }
        Map<UUID, String> paths = new HashMap<>();
        for (Folder f : byId.values()) {
            paths.put(f.getId(), pathOf(f, byId, new HashSet<>()));
        }
        return paths;
    }

    private String pathOf(Folder folder, Map<UUID, Folder> byId, Set<UUID> visited) {
        if (folder == null || !visited.add(folder.getId())) return "";
        Folder parent = folder.getParentFolderId() == null ? null : byId.get(folder.getParentFolderId());
        String parentPath = parent == null ? "" : pathOf(parent, byId, visited);
        return parentPath.isEmpty() ? folder.getName() : parentPath + "/" + folder.getName();
    }

    // 앱 재시작·네트워크 재연결 시 복구용: 아직 진행 중인 Job 목록.
    // FE는 이 목록으로 각 Job의 status를 조회하고 SSE를 다시 연결한다(탭 2개 이상 대응).
    @Transactional(readOnly = true)
    public List<JobResponseDto.JobCard> getActiveJobs(String userId) {
        UUID uid = UUID.fromString(userId);
        List<Job> jobs = jobRepository.findActiveByUserIdAndStatusIn(uid, List.of("PENDING", "IN_PROGRESS"));
        Map<UUID, String> folderPaths = buildFolderPaths(uid, jobs);
        List<JobResponseDto.JobCard> result = new ArrayList<>();
        for (Job job : jobs) {
            result.add(toCard(job, folderPaths));
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
                job.isInsertPageNumber(),
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

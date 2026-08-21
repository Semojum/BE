package com.semojum.backend.domain.admin.service;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.enums.Role;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.result.entity.BoundingBox;
import com.semojum.backend.domain.result.entity.BrailleElement;
import com.semojum.backend.domain.result.entity.PageResult;
import com.semojum.backend.domain.result.entity.QualityCriticalError;
import com.semojum.backend.domain.result.entity.QualityReviewFlag;
import com.semojum.backend.domain.result.entity.RuleTrail;
import com.semojum.backend.domain.result.entity.TextElement;
import com.semojum.backend.domain.result.repository.BoundingBoxRepository;
import com.semojum.backend.domain.result.repository.BrailleElementRepository;
import com.semojum.backend.domain.result.repository.PageResultRepository;
import com.semojum.backend.domain.result.repository.QualityCriticalErrorRepository;
import com.semojum.backend.domain.result.repository.QualityReviewFlagRepository;
import com.semojum.backend.domain.result.repository.RuleTrailRepository;
import com.semojum.backend.domain.result.repository.TextElementRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.s3.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T1-5 "마이페이지로 보내기" — 작업 사본을 운영자 계정의 마이페이지로 복사한다.
 * 미리보기 창은 확인용이므로(기획), 자세히 보려면 사본을 앱 에디터에서 연다.
 *
 * <p>사본 = Job·Page·PageResult·요소·bbox·규정·품질 행 전체 + S3 객체(원본 페이지·썸네일) 복사.
 * 편집 이력은 original/current를 그대로 보존해 옮긴다. page_edit_logs(RLHF 원천)는 복사하지 않는다.
 * 대상 계정은 ROLE_ADMIN만 — 고객 계정에 사본을 밀어 넣는 실수를 막는다.
 *
 * <p>V28: 웹 관리자(admin_scope=WEB)가 대상 미지정으로 호출하면 앱 관리자(admin_scope=APP) 전원에게
 * 배포한다 — 웹 관리자는 앱 로그인이 불가해 본인 마이페이지 사본이 무의미하기 때문. 사본 Job은
 * admin_copy=true + 원가 0(원가·원자료 미복사)으로 저장돼 통계·수익성에서 제외된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCopyService {

    private static final List<String> IN_FLIGHT = List.of("PENDING", "IN_PROGRESS");

    private final JobRepository jobRepository;
    private final PageRepository pageRepository;
    private final PageResultRepository pageResultRepository;
    private final TextElementRepository textElementRepository;
    private final BrailleElementRepository brailleElementRepository;
    private final BoundingBoxRepository boundingBoxRepository;
    private final RuleTrailRepository ruleTrailRepository;
    private final QualityCriticalErrorRepository qualityCriticalErrorRepository;
    private final QualityReviewFlagRepository qualityReviewFlagRepository;
    private final UserRepository userRepository;
    private final S3Service s3Service;

    /**
     * @param targetLoginId 사본을 받을 운영자 계정. null이면 웹 관리자는 앱 관리자 전원, 그 외는 본인
     */
    @Transactional
    public Map<String, Object> copyToMypage(String jobId, String targetLoginId, String authUserId) {
        Job source = jobRepository.findById(jobId)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        if (IN_FLIGHT.contains(source.getStatus())) {
            log.warn("진행 중 작업 사본 거부: jobId={}", jobId);
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }

        List<User> targets = resolveTargets(targetLoginId, authUserId);
        List<Map<String, Object>> copies = new java.util.ArrayList<>();
        for (User target : targets) {
            copies.add(copyTo(source, target));
        }
        return Map.of("copies", copies, "sourceJobId", jobId);
    }

    // 사본 1건 생성 — 대상 계정 하나에 Job 전체(페이지·결과·S3)를 복사
    private Map<String, Object> copyTo(Job source, User target) {
        String jobId = source.getId();

        // 새 Job (상태는 생성자 기본 PENDING → 행 복사 후 finishJob으로 원본 종료 상태 재현)
        String newJobId = "job_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Job copy = Job.builder()
                .id(newJobId)
                .user(target)
                .mode(source.getMode())
                .totalPages(source.getTotalPages())
                .originalFileName(source.getOriginalFileName() + " (관리자 사본)")
                .insertPageNumber(source.isInsertPageNumber())
                .footerText(source.getFooterText())
                .adminCopy(true)   // 통계(건수·쪽수·원가) 제외 표식 (V28)
                .build();
        jobRepository.saveAndFlush(copy);

        // 썸네일 복사 (공개 객체) — 실패해도 사본 생성은 계속
        try {
            String destThumb = newJobId + "/thumbnail.png";
            s3Service.copyObject(jobId + "/thumbnail.png", destThumb);
            copy.updateThumbnailUrl(s3Service.getPublicUrl(destThumb));
        } catch (Exception e) {
            log.warn("사본 썸네일 복사 실패(계속): jobId={}, error={}", jobId, e.getMessage());
        }

        // Page + 원본 S3 객체 복사
        Map<Integer, Page> newPages = new HashMap<>();
        for (Page page : pageRepository.findByJobId(jobId)) {
            String oldPath = page.getPdfPath();
            String ext = oldPath.substring(oldPath.lastIndexOf('.'));   // .pdf | .txt
            String destKey = newJobId + "/pages/page-" + page.getPageNo() + ext;
            String newPath = s3Service.copyObject(oldPath, destKey);
            Page newPage = Page.builder().job(copy).pageNo(page.getPageNo()).pdfPath(newPath).build();
            newPage.updateStatus(page.getStatus());
            newPages.put(page.getPageNo(), pageRepository.save(newPage));
        }

        // PageResult(재시도 중복은 최신만) + 하위 행 복사
        Map<Integer, PageResult> latest = new HashMap<>();
        for (PageResult pr : pageResultRepository.findByJobIdOrderByPageNumber(jobId)) {
            PageResult prev = latest.get(pr.getPageNumber());
            if (prev == null || pr.getCreatedAt().isAfter(prev.getCreatedAt())) latest.put(pr.getPageNumber(), pr);
        }
        int copiedResults = 0;
        for (PageResult pr : latest.values()) {
            Page newPage = newPages.get(pr.getPageNumber());
            if (newPage == null) continue;
            copiedResults++;
            PageResult newPr = pageResultRepository.save(PageResult.builder()
                    .job(copy).page(newPage)
                    .pageNumber(pr.getPageNumber()).mode(pr.getMode()).status(pr.getStatus())
                    .imageWidth(pr.getImageWidth()).imageHeight(pr.getImageHeight())
                    .ocrConfidenceAvg(pr.getOcrConfidenceAvg()).lineOverflowRate(pr.getLineOverflowRate())
                    .processingTimeMs(pr.getProcessingTimeMs()).pdfLayerConfidence(pr.getPdfLayerConfidence())
                    .routingTierUsed(pr.getRoutingTierUsed()).scanOnly(pr.getScanOnly())
                    .rawResponse(pr.getRawResponse())
                    // 사본 원가는 0 — 실제 변환이 없었으므로 원가·원자료를 복사하지 않는다 (이중 계상 방지, V28)
                    .layoutType(pr.getLayoutType()).gpuTimeMs(null).modelUsage(null)
                    .llmCostUsd(null).gpuCostUsd(null).costKrw(null)
                    .costUncertain(false).pricingConfigId(null)
                    .build());

            for (TextElement el : textElementRepository.findByPageResult(pr)) {
                TextElement newEl = TextElement.builder()
                        .pageResult(newPr).elementId(el.getElementId()).type(el.getType())
                        .readingOrder(el.getReadingOrder()).headingLevel(el.getHeadingLevel())
                        .ocrConfidence(el.getOcrConfidence()).tnText(el.getTnText())
                        .latexString(el.getLatexString()).selectedIdx(el.getSelectedIdx())
                        .renderMode(el.getRenderMode()).visualSubtype(el.getVisualSubtype())
                        .subtypeConfidence(el.getSubtypeConfidence())
                        .contents(el.getOriginalContents())   // original 보존
                        .drafts(el.getDrafts()).isBlocked(el.isBlocked())
                        .build();
                newEl.updateCurrentContents(el.getCurrentContents());   // 편집본 보존
                textElementRepository.save(newEl);
                copyRuleTrails(el.getId(), newEl.getId(), "TEXT");
            }
            for (BrailleElement el : brailleElementRepository.findByPageResult(pr)) {
                BrailleElement newEl = BrailleElement.builder()
                        .pageResult(newPr).elementId(el.getElementId()).type(el.getType())
                        .readingOrder(el.getReadingOrder()).headingLevel(el.getHeadingLevel())
                        .ocrConfidence(el.getOcrConfidence()).tnText(el.getTnText())
                        .latexString(el.getLatexString()).selectedIdx(el.getSelectedIdx())
                        .renderMode(el.getRenderMode()).visualSubtype(el.getVisualSubtype())
                        .subtypeConfidence(el.getSubtypeConfidence())
                        .content(el.getOriginalContent())
                        .drafts(el.getDrafts()).isBlocked(el.isBlocked())
                        .build();
                newEl.updateCurrentContent(el.getCurrentContent());
                brailleElementRepository.save(newEl);
                copyRuleTrails(el.getId(), newEl.getId(), "BRAILLE");
            }
            for (BoundingBox box : boundingBoxRepository.findByPageResult(pr)) {
                boundingBoxRepository.save(BoundingBox.builder()
                        .pageResult(newPr).elementId(box.getElementId())
                        .x(box.getX()).y(box.getY()).x2(box.getX2()).y2(box.getY2())
                        .type(box.getType()).headingLevel(box.getHeadingLevel())
                        .captionRef(box.getCaptionRef()).flags(box.getFlags())
                        .build());
            }
            for (QualityCriticalError err : qualityCriticalErrorRepository.findByPageResult(pr)) {
                qualityCriticalErrorRepository.save(QualityCriticalError.builder()
                        .pageResult(newPr).type(err.getType())
                        .elementId(err.getElementId()).message(err.getMessage()).build());
            }
            for (QualityReviewFlag flag : qualityReviewFlagRepository.findByPageResult(pr)) {
                qualityReviewFlagRepository.save(QualityReviewFlag.builder()
                        .pageResult(newPr).type(flag.getType())
                        .elementId(flag.getElementId()).message(flag.getMessage()).build());
            }
        }

        // 원본의 종료 상태 재현 (finishJob 가드: 새 Job은 PENDING이라 통과)
        jobRepository.finishJob(newJobId, source.getStatus(), toPgIntArray(source.getFailedPages()));

        log.info("관리자 사본 생성: source={}, copy={}, target={}, 페이지 {}장·결과 {}건",
                jobId, newJobId, target.getLoginId(), newPages.size(), copiedResults);
        return Map.of("jobId", newJobId, "targetLoginId", target.getLoginId(),
                "pages", newPages.size(), "results", copiedResults);
    }

    private void copyRuleTrails(UUID oldElementId, UUID newElementId, String elementType) {
        for (RuleTrail rt : ruleTrailRepository.findByElementId(oldElementId)) {
            ruleTrailRepository.save(RuleTrail.builder()
                    .elementId(newElementId).elementType(elementType)
                    .ruleId(rt.getRuleId()).source(rt.getSource()).priority(rt.getPriority())
                    .section(rt.getSection()).title(rt.getTitle()).excerpt(rt.getExcerpt())
                    .lineNo(rt.getLineNo()).colStart(rt.getColStart()).colEnd(rt.getColEnd())
                    .tag(rt.getTag())
                    .build());
        }
    }

    // 대상은 운영자 계정만 — 고객 계정으로의 오전송 방지.
    // 대상 미지정 시: 웹 관리자(WEB)는 앱 관리자(APP) 활성 계정 전원 / 그 외 관리자는 본인 (V28)
    private List<User> resolveTargets(String targetLoginId, String authUserId) {
        if (targetLoginId != null && !targetLoginId.isBlank()) {
            User target = userRepository.findByLoginId(targetLoginId)
                    .filter(u -> u.getDeletedAt() == null)
                    .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
            requireAdmin(target);
            return List.of(target);
        }
        if (authUserId == null) {
            // JWT 전용 전환(2026-08-19) 후 도달 불가 — 방어적으로 유지
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        User caller = userRepository.findById(UUID.fromString(authUserId))
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        requireAdmin(caller);
        if (User.ADMIN_SCOPE_WEB.equals(caller.getAdminScope())) {
            List<User> targets = userRepository
                    .findByRoleAndAdminScopeAndDeletedAtIsNull(Role.ROLE_ADMIN, User.ADMIN_SCOPE_APP)
                    .stream().filter(User::isActive).toList();
            if (targets.isEmpty()) {
                log.warn("배포할 앱 관리자(admin_scope=APP) 계정이 없음");
                throw new CustomException(ErrorCode.USER_NOT_FOUND);
            }
            return targets;
        }
        return List.of(caller);
    }

    private void requireAdmin(User user) {
        if (user.getRole() != Role.ROLE_ADMIN) {
            log.warn("사본 대상이 운영자 계정 아님: {}", user.getLoginId());
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
    }

    private String toPgIntArray(int[] nums) {
        StringBuilder sb = new StringBuilder("{");
        if (nums != null) {
            for (int i = 0; i < nums.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(nums[i]);
            }
        }
        return sb.append("}").toString();
    }
}

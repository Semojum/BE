package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.result.entity.*;
import com.semojum.backend.domain.result.repository.*;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 받은 .smj 파일을 저장할 때의 사본 작업 (2026-10-21 결정).
 *
 * <p>메모 협업은 파일 전달이다 — 파일을 받은 사람이 답글을 달고 저장하면, 그 작업은 남의 계정에 묶여 있다.
 * 그래서 <b>저장하는 사람 계정에 사본을 만들고 거기에 저장</b>한다. 이후 저장은 앱이 사본 jobId로 보낸다.
 *
 * <ul>
 *   <li><b>전부 복사</b> — 쪽 · 쪽 결과 · 텍스트/점자 블록(원본 · 현재) · 대체 초안 · bbox · 규정 · 품질 · 메모 · 답글.
 *       받은 사람도 원본 패널과 규정 보기를 그대로 쓴다</li>
 *   <li><b>S3 파일은 복사하지 않고 같은 객체를 가리킨다</b> — 쪽 PDF · 미리보기 · 썸네일은 만든 뒤 바뀌지 않고,
 *       작업 삭제 경로가 없어 원본이 먼저 사라질 일이 없다. 205쪽이면 복사만 400번이다</li>
 *   <li><b>파일이 있는 누구나</b> 만들 수 있다(기관 제한 없음, 기획 결정) — 작업 ID를 아는 것 = 파일을 가진 것</li>
 *   <li>변환이 아니다 — 크레딧 차감 없음, 원가 0, {@code admin_copy=true}로 통계 · 사용량에서 빠진다</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobCopyService {

    private final JobRepository jobRepository;
    private final PageRepository pageRepository;
    private final PageResultRepository pageResultRepository;
    private final TextElementRepository textElementRepository;
    private final BrailleElementRepository brailleElementRepository;
    private final BoundingBoxRepository boundingBoxRepository;
    private final RuleTrailRepository ruleTrailRepository;
    private final QualityCriticalErrorRepository qualityCriticalErrorRepository;
    private final QualityReviewFlagRepository qualityReviewFlagRepository;
    private final PageMemoRepository pageMemoRepository;
    private final PageMemoReplyRepository pageMemoReplyRepository;

    /** {@code source}의 사본을 {@code target} 계정에 만든다. 변환 중인 작업은 JOB4010 */
    @Transactional
    public Job copyFor(User target, Job source) {
        if (source.isInProgress()) throw new CustomException(ErrorCode.JOB_IN_PROGRESS);
        String sourceId = source.getId();

        String newJobId = "job_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Job copy = Job.builder()
                .id(newJobId)
                .user(target)
                .mode(source.getMode())
                .totalPages(source.getTotalPages())
                .originalFileName(source.getOriginalFileName())
                .thumbnailUrl(source.getThumbnailUrl())
                .insertPageNumber(source.isInsertPageNumber())
                .footerText(source.getFooterText())
                .layoutOptions(source.getLayoutOptions())
                .brailleOptions(source.getBrailleOptions())
                .adminCopy(true)   // 변환이 아니다 — 통계 · 원가 · 사용량에서 뺀다
                .build();
        copy.updateFooterBraille(source.getFooterBraille());
        jobRepository.saveAndFlush(copy);

        Map<UUID, UUID> pageIds = new HashMap<>();
        Map<Integer, Page> newPages = new HashMap<>();
        for (Page page : pageRepository.findByJobId(sourceId)) {
            Page p = Page.builder().job(copy).pageNo(page.getPageNo()).pdfPath(page.getPdfPath()).build();
            p.updateStatus(page.getStatus());
            p.updateImagePath(page.getImagePath());
            p = pageRepository.save(p);
            newPages.put(page.getPageNo(), p);
            pageIds.put(page.getId(), p.getId());
        }

        // 재시도로 같은 쪽 결과가 둘 이상이면 최신 것만
        Map<Integer, PageResult> latest = new HashMap<>();
        for (PageResult pr : pageResultRepository.findByJobIdOrderByPageNumber(sourceId)) {
            PageResult prev = latest.get(pr.getPageNumber());
            if (prev == null || pr.getCreatedAt().isAfter(prev.getCreatedAt())) latest.put(pr.getPageNumber(), pr);
        }
        for (PageResult pr : latest.values()) {
            Page newPage = newPages.get(pr.getPageNumber());
            if (newPage == null) continue;
            copyResult(copy, newPage, pr);
        }
        copyMemos(pageIds);

        // 원본의 종료 상태 재현 (finishJob 가드: 새 Job은 PENDING이라 통과)
        jobRepository.finishJob(newJobId, source.getStatus(), pgIntArray(source.getFailedPages()));
        log.info("받은 파일 사본 생성: source={}, copy={}, target={}, 쪽 {}장 · 결과 {}건",
                sourceId, newJobId, target.getLoginId(), newPages.size(), latest.size());
        return jobRepository.findById(newJobId).orElse(copy);
    }

    private void copyResult(Job copy, Page newPage, PageResult pr) {
        PageResult newPr = pageResultRepository.save(PageResult.builder()
                .job(copy).page(newPage)
                .pageNumber(pr.getPageNumber()).mode(pr.getMode()).status(pr.getStatus())
                .imageWidth(pr.getImageWidth()).imageHeight(pr.getImageHeight())
                .ocrConfidenceAvg(pr.getOcrConfidenceAvg()).lineOverflowRate(pr.getLineOverflowRate())
                .processingTimeMs(pr.getProcessingTimeMs()).pdfLayerConfidence(pr.getPdfLayerConfidence())
                .routingTierUsed(pr.getRoutingTierUsed()).scanOnly(pr.getScanOnly())
                .rawResponse(pr.getRawResponse())
                // 원가는 0 — 실제 변환이 없었다(이중 계상 방지)
                .layoutType(pr.getLayoutType()).gpuTimeMs(null).modelUsage(null)
                .llmCostUsd(null).gpuCostUsd(null).costKrw(null)
                .costUncertain(false).pricingConfigId(null)
                .build());

        Map<UUID, UUID> elementIds = new HashMap<>();
        Map<UUID, String> elementTypes = new HashMap<>();
        for (TextElement el : textElementRepository.findByPageResult(pr)) {
            TextElement n = TextElement.builder()
                    .pageResult(newPr).elementId(el.getElementId()).type(el.getType())
                    .readingOrder(el.getReadingOrder()).headingLevel(el.getHeadingLevel())
                    .ocrConfidence(el.getOcrConfidence()).tnText(el.getTnText())
                    .latexString(el.getLatexString()).selectedIdx(el.getSelectedIdx())
                    .renderMode(el.getRenderMode()).visualSubtype(el.getVisualSubtype())
                    .subtypeConfidence(el.getSubtypeConfidence())
                    .contents(el.getOriginalContents())   // AI 원본 보존
                    .drafts(el.getDrafts()).isBlocked(el.isBlocked())
                    .build();
            n.updateCurrentContents(el.getCurrentContents());   // 편집본 보존
            textElementRepository.save(n);
            elementIds.put(el.getId(), n.getId());
            elementTypes.put(el.getId(), "TEXT");
        }
        for (BrailleElement el : brailleElementRepository.findByPageResult(pr)) {
            BrailleElement n = BrailleElement.builder()
                    .pageResult(newPr).elementId(el.getElementId()).type(el.getType())
                    .readingOrder(el.getReadingOrder()).headingLevel(el.getHeadingLevel())
                    .ocrConfidence(el.getOcrConfidence()).tnText(el.getTnText())
                    .latexString(el.getLatexString()).selectedIdx(el.getSelectedIdx())
                    .renderMode(el.getRenderMode()).visualSubtype(el.getVisualSubtype())
                    .subtypeConfidence(el.getSubtypeConfidence())
                    .content(el.getOriginalContent())
                    .drafts(el.getDrafts()).isBlocked(el.isBlocked())
                    .build();
            n.updateCurrentContent(el.getCurrentContent());
            brailleElementRepository.save(n);
            elementIds.put(el.getId(), n.getId());
            elementTypes.put(el.getId(), "BRAILLE");
        }
        // 규정은 쪽 단위로 한 번에 읽는다(요소마다 읽으면 N+1)
        if (!elementIds.isEmpty()) {
            for (RuleTrail rt : ruleTrailRepository.findByElementIdIn(elementIds.keySet())) {
                ruleTrailRepository.save(RuleTrail.builder()
                        .elementId(elementIds.get(rt.getElementId())).elementType(rt.getElementType())
                        .ruleId(rt.getRuleId()).source(rt.getSource()).priority(rt.getPriority())
                        .section(rt.getSection()).title(rt.getTitle()).excerpt(rt.getExcerpt())
                        .lineNo(rt.getLineNo()).colStart(rt.getColStart()).colEnd(rt.getColEnd())
                        .tag(rt.getTag()).publisher(rt.getPublisher()).version(rt.getVersion())
                        .sectionPath(rt.getSectionPath())
                        .build());
            }
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

    /** 메모 · 답글 — 저장 본문에 메모가 안 실린 쪽도 원본 메모가 남도록 함께 옮긴다 */
    private void copyMemos(Map<UUID, UUID> pageIds) {
        for (var e : pageIds.entrySet()) {
            List<PageMemo> memos = pageMemoRepository.findByPageIdOrderByElementIdAscLineIndexAsc(e.getKey());
            if (memos.isEmpty()) continue;
            Map<Long, List<PageMemoReply>> replies = new HashMap<>();
            for (PageMemoReply r : pageMemoReplyRepository.findByMemoIdInOrderByIdAsc(
                    memos.stream().map(PageMemo::getId).toList())) {
                replies.computeIfAbsent(r.getMemoId(), k -> new ArrayList<>()).add(r);
            }
            for (PageMemo m : memos) {
                PageMemo n = pageMemoRepository.save(PageMemo.builder()
                        .pageId(e.getValue()).panel(m.getPanel()).elementId(m.getElementId())
                        .lineIndex(m.getLineIndex()).lineText(m.getLineText()).body(m.getBody())
                        .createdAt(m.getCreatedAt()).author(m.getAuthor())
                        .resolved(m.isResolved()).resolvedAt(m.getResolvedAt())
                        .build());
                for (PageMemoReply r : replies.getOrDefault(m.getId(), List.of())) {
                    pageMemoReplyRepository.save(PageMemoReply.builder()
                            .memoId(n.getId()).author(r.getAuthor()).body(r.getBody()).createdAt(r.getCreatedAt())
                            .build());
                }
            }
        }
    }

    private static String pgIntArray(int[] nums) {
        StringJoiner j = new StringJoiner(",", "{", "}");
        if (nums != null) for (int n : nums) j.add(String.valueOf(n));
        return j.toString();
    }
}

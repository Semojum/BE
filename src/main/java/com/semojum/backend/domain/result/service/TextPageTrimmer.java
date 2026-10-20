package com.semojum.backend.domain.result.service;

import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.result.entity.PageMemo;
import com.semojum.backend.domain.result.entity.PageResult;
import com.semojum.backend.domain.result.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 텍스트 작업(TXT · 새 문서)의 쪽 줄이기 (2026-10-21, FE 점검 B-02).
 *
 * <p>쪽 수는 앱이 저장 본문의 {@code totalPages}로 <b>명시했을 때만</b> 줄인다. 종전엔 「이번 요청에서
 * 성공한 마지막 쪽」으로 덮어써, 3쪽 문서의 2 · 3쪽 저장이 실패하면 1쪽짜리가 됐다(쪽 행은 남은 채로).
 *
 * <p>그 뒤 쪽은 <b>통째로 지운다</b> — 쪽 · 쪽 결과 · 블록(삭제 표시분 포함) · 규정 · bbox · 품질 · 메모 · 답글.
 * 숫자만 줄이면 쪽 조회와 쪽 수가 어긋난다. PDF · 이미지 작업은 원본 쪽이라 이 경로를 타지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TextPageTrimmer {

    private final PageRepository pageRepository;
    private final PageResultRepository pageResultRepository;
    private final TextElementRepository textElementRepository;
    private final BrailleElementRepository brailleElementRepository;
    private final RuleTrailRepository ruleTrailRepository;
    private final BoundingBoxRepository boundingBoxRepository;
    private final QualityCriticalErrorRepository qualityCriticalErrorRepository;
    private final QualityReviewFlagRepository qualityReviewFlagRepository;
    private final PageMemoRepository pageMemoRepository;
    private final PageMemoReplyRepository pageMemoReplyRepository;

    /** {@code lastPage}보다 뒤의 쪽을 지운다. 지운 쪽 수를 돌려준다 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deletePagesAfter(Job job, int lastPage) {
        List<Page> pages = pageRepository.findByJobId(job.getId()).stream()
                .filter(p -> p.getPageNo() > lastPage).toList();
        if (pages.isEmpty()) return 0;
        List<UUID> pageIds = pages.stream().map(Page::getId).toList();
        List<UUID> resultIds = pageResultRepository.findByJobIdOrderByPageNumber(job.getId()).stream()
                .filter(r -> r.getPageNumber() > lastPage).map(PageResult::getId).toList();

        if (!resultIds.isEmpty()) {
            List<UUID> elementIds = new ArrayList<>(textElementRepository.findIdsByPageResultIdIn(resultIds));
            elementIds.addAll(brailleElementRepository.findIdsByPageResultIdIn(resultIds));
            if (!elementIds.isEmpty()) ruleTrailRepository.deleteByElementIdIn(elementIds);
            textElementRepository.deleteByPageResultIdIn(resultIds);
            brailleElementRepository.deleteByPageResultIdIn(resultIds);
            boundingBoxRepository.deleteByPageResultIdIn(resultIds);
            qualityCriticalErrorRepository.deleteByPageResultIdIn(resultIds);
            qualityReviewFlagRepository.deleteByPageResultIdIn(resultIds);
            pageResultRepository.deleteAllById(resultIds);
        }
        List<Long> memoIds = pageMemoRepository.findByPageIdIn(pageIds).stream().map(PageMemo::getId).toList();
        if (!memoIds.isEmpty()) pageMemoReplyRepository.deleteByMemoIdIn(memoIds);
        pageMemoRepository.deleteByPageIdIn(pageIds);
        pageRepository.deleteAll(pages);

        log.info("텍스트 작업 쪽 줄이기: jobId={}, {}쪽 뒤 {}쪽 삭제", job.getId(), lastPage, pages.size());
        return pages.size();
    }
}

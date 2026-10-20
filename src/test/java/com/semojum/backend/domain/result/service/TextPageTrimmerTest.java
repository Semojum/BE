package com.semojum.backend.domain.result.service;

import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.result.entity.PageMemo;
import com.semojum.backend.domain.result.entity.PageResult;
import com.semojum.backend.domain.result.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 텍스트 작업 쪽 줄이기 (FE 점검 B-02) — 그 뒤 쪽만, 딸린 것까지 통째로 지운다 */
class TextPageTrimmerTest {

    PageRepository pageRepo = mock(PageRepository.class);
    PageResultRepository resultRepo = mock(PageResultRepository.class);
    TextElementRepository textRepo = mock(TextElementRepository.class);
    BrailleElementRepository brailleRepo = mock(BrailleElementRepository.class);
    RuleTrailRepository ruleRepo = mock(RuleTrailRepository.class);
    BoundingBoxRepository boxRepo = mock(BoundingBoxRepository.class);
    QualityCriticalErrorRepository criticalRepo = mock(QualityCriticalErrorRepository.class);
    QualityReviewFlagRepository flagRepo = mock(QualityReviewFlagRepository.class);
    PageMemoRepository memoRepo = mock(PageMemoRepository.class);
    PageMemoReplyRepository replyRepo = mock(PageMemoReplyRepository.class);
    TextPageTrimmer trimmer = new TextPageTrimmer(pageRepo, resultRepo, textRepo, brailleRepo, ruleRepo, boxRepo,
            criticalRepo, flagRepo, memoRepo, replyRepo);

    Job job = Job.builder().id("job-t").mode("b").totalPages(3).originalFileName("무제").build();
    Page p1, p2, p3;
    PageResult r1, r2, r3;

    static <T> T withId(T e, Object id) { ReflectionTestUtils.setField(e, "id", id); return e; }

    @BeforeEach
    void setUp() {
        p1 = withId(Page.builder().job(job).pageNo(1).pdfPath("").build(), UUID.randomUUID());
        p2 = withId(Page.builder().job(job).pageNo(2).pdfPath("").build(), UUID.randomUUID());
        p3 = withId(Page.builder().job(job).pageNo(3).pdfPath("").build(), UUID.randomUUID());
        r1 = withId(PageResult.builder().job(job).page(p1).pageNumber(1).mode("b").status("SAVED").build(), UUID.randomUUID());
        r2 = withId(PageResult.builder().job(job).page(p2).pageNumber(2).mode("b").status("SAVED").build(), UUID.randomUUID());
        r3 = withId(PageResult.builder().job(job).page(p3).pageNumber(3).mode("b").status("SAVED").build(), UUID.randomUUID());
        when(pageRepo.findByJobId("job-t")).thenReturn(List.of(p1, p2, p3));
        when(resultRepo.findByJobIdOrderByPageNumber("job-t")).thenReturn(List.of(r1, r2, r3));
    }

    @Test
    void 뒤_쪽만_딸린_것까지_지운다() {
        UUID el = UUID.randomUUID();
        when(textRepo.findIdsByPageResultIdIn(List.of(r3.getId()))).thenReturn(List.of(el));
        PageMemo memo = withId(PageMemo.builder().pageId(p3.getId()).panel("TEXT").elementId("e").lineIndex(0).body("m").build(), 7L);
        when(memoRepo.findByPageIdIn(List.of(p3.getId()))).thenReturn(List.of(memo));

        assertEquals(1, trimmer.deletePagesAfter(job, 2));

        verify(ruleRepo).deleteByElementIdIn(List.of(el));
        verify(textRepo).deleteByPageResultIdIn(List.of(r3.getId()));
        verify(brailleRepo).deleteByPageResultIdIn(List.of(r3.getId()));
        verify(resultRepo).deleteAllById(List.of(r3.getId()));
        verify(replyRepo).deleteByMemoIdIn(List.of(7L));
        verify(memoRepo).deleteByPageIdIn(List.of(p3.getId()));
        verify(pageRepo).deleteAll(List.of(p3));
    }

    @Test
    void 지울_쪽이_없으면_아무것도_안_한다() {
        assertEquals(0, trimmer.deletePagesAfter(job, 3));

        verify(pageRepo, never()).deleteAll(any());
        verify(resultRepo, never()).deleteAllById(any());
    }
}

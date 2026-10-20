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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 받은 .smj 파일을 저장할 때의 사본 (2026-10-21).
 *
 * <p>지킬 것: 내용 전부(원본 · 편집본 · 규정 · 메모 · 답글)를 옮긴다 · S3 파일은 같은 객체를 가리킨다 ·
 * 변환이 아니므로 원가 0이고 통계에서 빠진다(admin_copy) · 변환 중인 작업은 복사하지 않는다.
 */
class JobCopyServiceTest {

    JobRepository jobRepo;
    PageRepository pageRepo;
    PageResultRepository resultRepo;
    TextElementRepository textRepo;
    BrailleElementRepository brailleRepo;
    BoundingBoxRepository boxRepo;
    RuleTrailRepository ruleRepo;
    QualityCriticalErrorRepository criticalRepo;
    QualityReviewFlagRepository flagRepo;
    PageMemoRepository memoRepo;
    PageMemoReplyRepository replyRepo;
    JobCopyService service;

    User owner, receiver;
    Job source;
    Page srcPage;
    PageResult srcResult;
    TextElement srcText;

    static <T> T withId(T entity, Object id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    @BeforeEach
    void setUp() {
        jobRepo = mock(JobRepository.class);
        pageRepo = mock(PageRepository.class);
        resultRepo = mock(PageResultRepository.class);
        textRepo = mock(TextElementRepository.class);
        brailleRepo = mock(BrailleElementRepository.class);
        boxRepo = mock(BoundingBoxRepository.class);
        ruleRepo = mock(RuleTrailRepository.class);
        criticalRepo = mock(QualityCriticalErrorRepository.class);
        flagRepo = mock(QualityReviewFlagRepository.class);
        memoRepo = mock(PageMemoRepository.class);
        replyRepo = mock(PageMemoReplyRepository.class);
        service = new JobCopyService(jobRepo, pageRepo, resultRepo, textRepo, brailleRepo, boxRepo, ruleRepo,
                criticalRepo, flagRepo, memoRepo, replyRepo);

        owner = withId(User.builder().loginId("testorg01").password("pw").build(), UUID.randomUUID());
        receiver = withId(User.builder().loginId("testorg02").password("pw").build(), UUID.randomUUID());

        source = Job.builder().id("job_src").user(owner).mode("c").totalPages(1).originalFileName("교재.pdf")
                .thumbnailUrl("https://b/job_src/thumbnail.png").footerText("수특").build();
        source.complete(new int[]{});
        srcPage = withId(Page.builder().job(source).pageNo(1).pdfPath("s3://b/job_src/pages/page-1.pdf").build(), UUID.randomUUID());
        srcPage.updateImagePath("s3://b/job_src/pages/page-1.jpg");
        srcResult = withId(PageResult.builder().job(source).page(srcPage).pageNumber(1).mode("c").status("COMPLETED")
                .layoutType("TEXT").costKrw(new BigDecimal("12.5")).pricingConfigId(3L).build(), UUID.randomUUID());
        srcText = withId(TextElement.builder().pageResult(srcResult).elementId("t1").type("text")
                .contents(List.of("AI 원본")).build(), UUID.randomUUID());
        srcText.updateCurrentContents(List.of("고친 글"));

        when(jobRepo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(jobRepo.findById(anyString())).thenReturn(Optional.empty());
        when(pageRepo.findByJobId("job_src")).thenReturn(List.of(srcPage));
        when(pageRepo.save(any())).thenAnswer(i -> withId(i.getArgument(0), UUID.randomUUID()));
        when(resultRepo.findByJobIdOrderByPageNumber("job_src")).thenReturn(List.of(srcResult));
        when(resultRepo.save(any())).thenAnswer(i -> withId(i.getArgument(0), UUID.randomUUID()));
        when(textRepo.findByPageResult(srcResult)).thenReturn(List.of(srcText));
        when(textRepo.save(any())).thenAnswer(i -> withId(i.getArgument(0), UUID.randomUUID()));
        when(memoRepo.save(any())).thenAnswer(i -> withId(i.getArgument(0), 900L));
    }

    @Test
    void 사본은_받은_사람_것이고_통계에서_빠진다() {
        Job copy = service.copyFor(receiver, source);

        assertNotEquals("job_src", copy.getId());
        assertSame(receiver, copy.getUser());
        assertTrue(copy.isAdminCopy(), "변환이 아니다 — 통계 · 원가 · 사용량에서 뺀다");
        assertEquals("수특", copy.getFooterText());
        assertEquals("https://b/job_src/thumbnail.png", copy.getThumbnailUrl(), "썸네일도 같은 객체");
        verify(jobRepo).finishJob(eq(copy.getId()), eq("COMPLETED"), eq("{}"));
    }

    /** S3는 복사하지 않는다 — 쪽 PDF · 미리보기는 같은 객체를 가리킨다 */
    @Test
    void 쪽은_같은_S3_파일을_가리킨다() {
        service.copyFor(receiver, source);

        ArgumentCaptor<Page> page = ArgumentCaptor.forClass(Page.class);
        verify(pageRepo).save(page.capture());
        assertEquals("s3://b/job_src/pages/page-1.pdf", page.getValue().getPdfPath());
        assertEquals("s3://b/job_src/pages/page-1.jpg", page.getValue().getImagePath());
    }

    @Test
    void 쪽_결과의_원가는_0이다() {
        service.copyFor(receiver, source);

        ArgumentCaptor<PageResult> result = ArgumentCaptor.forClass(PageResult.class);
        verify(resultRepo).save(result.capture());
        assertNull(result.getValue().getCostKrw(), "원본 원가를 옮기면 이중 계상이다");
        assertNull(result.getValue().getPricingConfigId());
        assertEquals("TEXT", result.getValue().getLayoutType());
    }

    /** 블록은 AI 원본과 편집본을 둘 다 옮기고, 규정은 새 블록을 가리킨다 */
    @Test
    void 블록과_규정을_옮긴다() {
        RuleTrail rule = RuleTrail.builder().elementId(srcText.getId()).elementType("TEXT").ruleId("MCST-1").build();
        when(ruleRepo.findByElementIdIn(any())).thenReturn(List.of(rule));

        service.copyFor(receiver, source);

        ArgumentCaptor<TextElement> text = ArgumentCaptor.forClass(TextElement.class);
        verify(textRepo).save(text.capture());
        TextElement copied = text.getValue();
        assertEquals("t1", copied.getElementId(), "앱이 쓰는 블록 id는 그대로 — 파일의 블록과 짝이 맞아야 한다");
        assertEquals(List.of("AI 원본"), copied.getOriginalContents());
        assertEquals(List.of("고친 글"), copied.getCurrentContents());

        ArgumentCaptor<RuleTrail> trail = ArgumentCaptor.forClass(RuleTrail.class);
        verify(ruleRepo).save(trail.capture());
        assertEquals(copied.getId(), trail.getValue().getElementId(), "규정은 사본 블록을 가리킨다");
        assertNotEquals(srcText.getId(), trail.getValue().getElementId());
    }

    @Test
    void 메모와_답글을_옮긴다() {
        PageMemo memo = withId(PageMemo.builder().pageId(srcPage.getId()).panel("TEXT").elementId("t1").lineIndex(0)
                .body("원문 확인").author("김점역").resolved(true).build(), 10L);
        when(memoRepo.findByPageIdOrderByElementIdAscLineIndexAsc(srcPage.getId())).thenReturn(List.of(memo));
        when(replyRepo.findByMemoIdInOrderByIdAsc(List.of(10L))).thenReturn(List.of(
                PageMemoReply.builder().memoId(10L).author("이교정").body("확인했습니다").build()));

        service.copyFor(receiver, source);

        ArgumentCaptor<PageMemo> m = ArgumentCaptor.forClass(PageMemo.class);
        verify(memoRepo).save(m.capture());
        assertNotEquals(srcPage.getId(), m.getValue().getPageId(), "사본 쪽에 붙는다");
        assertEquals("김점역", m.getValue().getAuthor());
        assertTrue(m.getValue().isResolved());

        ArgumentCaptor<PageMemoReply> r = ArgumentCaptor.forClass(PageMemoReply.class);
        verify(replyRepo).save(r.capture());
        assertEquals(900L, r.getValue().getMemoId(), "답글은 사본 메모에 붙는다");
        assertEquals("이교정", r.getValue().getAuthor());
    }

    @Test
    void 변환_중인_작업은_복사하지_않는다() {
        Job running = Job.builder().id("job_run").user(owner).mode("c").totalPages(1).originalFileName("a.pdf").build();

        CustomException e = assertThrows(CustomException.class, () -> service.copyFor(receiver, running));
        assertEquals(ErrorCode.JOB_IN_PROGRESS, e.getErrorCode());
        verify(jobRepo, never()).saveAndFlush(any());
    }
}

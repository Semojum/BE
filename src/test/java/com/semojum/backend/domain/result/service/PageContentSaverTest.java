package com.semojum.backend.domain.result.service;

import com.semojum.backend.domain.job.dto.JobRequestDto;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.result.entity.*;
import com.semojum.backend.domain.result.repository.*;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 작업 저장의 쪽 하나 — 패널 diff와 줄 메모.
 *
 * <p>구 저장 경로와 다른 점: <b>AI를 부르지 않는다.</b> FE 점역 엔진이 텍스트·점자를 모두 만들어
 * 보내므로 서버는 받은 대로 쓴다. 텍스트를 고쳤다고 점자가 따라 바뀌지 않는다.
 */
class PageContentSaverTest {

    PageRepository pageRepo;
    PageResultRepository pageResultRepo;
    TextElementRepository textRepo;
    BrailleElementRepository brailleRepo;
    PageMemoRepository memoRepo;
    com.semojum.backend.domain.result.repository.PageMemoReplyRepository replyRepo;
    PageContentSaver saver;

    Job job;
    PageResult pageResult;
    UUID pageId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        pageRepo = Mockito.mock(PageRepository.class);
        pageResultRepo = Mockito.mock(PageResultRepository.class);
        textRepo = Mockito.mock(TextElementRepository.class);
        brailleRepo = Mockito.mock(BrailleElementRepository.class);
        memoRepo = Mockito.mock(PageMemoRepository.class);
        replyRepo = Mockito.mock(com.semojum.backend.domain.result.repository.PageMemoReplyRepository.class);
        saver = new PageContentSaver(pageRepo, pageResultRepo, textRepo, brailleRepo, memoRepo, replyRepo);
        when(memoRepo.save(any())).thenAnswer(i -> {
            PageMemo m = i.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(m, "id", 501L);
            return m;
        });

        job = Job.builder().id("job1").mode("c").totalPages(1).originalFileName("f").build();
        pageResult = PageResult.builder().pageNumber(1).mode("c").status("COMPLETED").build();
        when(pageResultRepo.findByJobIdAndPageNumber(anyString(), anyInt()))
                .thenReturn(Optional.of(pageResult));
        when(pageRepo.findByJobAndPageNo(any(), anyInt()))
                .thenReturn(Optional.of(Page.builder().job(job).pageNo(1).build()));
        when(textRepo.findByPageResult(any())).thenReturn(List.of());
        when(brailleRepo.findByPageResult(any())).thenReturn(List.of());
        when(textRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(brailleRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(memoRepo.findByPageIdOrderByElementIdAscLineIndexAsc(any())).thenReturn(List.of());
    }

    private TextElement textEl(String id, int order, String body) {
        return TextElement.builder().pageResult(pageResult).elementId(id)
                .type("text").readingOrder(order).contents(List.of(body)).isBlocked(false).build();
    }

    private BrailleElement brailleEl(String id, int order, String body) {
        return BrailleElement.builder().pageResult(pageResult).elementId(id)
                .type("text").readingOrder(order).content(List.of(body)).isBlocked(false).build();
    }

    private JobRequestDto.SaveElement item(String id, String body) {
        return new JobRequestDto.SaveElement(id, List.of(body), null);
    }

    private JobRequestDto.SaveElement draftPick(String id, String body, Integer selectedIdx) {
        return new JobRequestDto.SaveElement(id, List.of(body), selectedIdx);
    }

    private JobRequestDto.SaveJobPage page(List<JobRequestDto.SaveElement> text,
                                           List<JobRequestDto.SaveElement> braille) {
        return new JobRequestDto.SaveJobPage(1, text, braille, null);
    }

    // ── 패널 diff ───────────────────────────────────────────────────

    @Test
    void 배열에서_빠진_블록은_삭제된다() {
        TextElement e1 = textEl("e1", 1, "남김");
        TextElement e2 = textEl("e2", 2, "지움");
        when(textRepo.findByPageResult(any())).thenReturn(List.of(e1, e2));

        boolean changed = saver.savePage(job, page(List.of(item("e1", "남김")), null));

        assertTrue(e2.isDeleted());
        assertFalse(e1.isDeleted());
        assertTrue(changed);
    }

    @Test
    void id가_없으면_새_블록을_만들고_서버가_id를_발급한다() {
        when(textRepo.findByPageResult(any())).thenReturn(List.of(textEl("e1", 1, "기존")));

        saver.savePage(job, page(List.of(item("e1", "기존"), item(null, "새 블록")), null));

        ArgumentCaptor<TextElement> captor = ArgumentCaptor.forClass(TextElement.class);
        verify(textRepo).save(captor.capture());
        TextElement neo = captor.getValue();
        assertNotNull(neo.getElementId());
        assertEquals(List.of("새 블록"), neo.getCurrentContents());
        assertNull(neo.getOriginalContents(), "사용자 블록엔 AI 원본이 없다");
    }

    @Test
    void 순서가_바뀌면_재번호한다() {
        TextElement e1 = textEl("e1", 1, "가");
        TextElement e2 = textEl("e2", 2, "나");
        when(textRepo.findByPageResult(any())).thenReturn(List.of(e1, e2));

        boolean changed = saver.savePage(job, page(List.of(item("e2", "나"), item("e1", "가")), null));

        assertEquals(1, e2.getReadingOrder());
        assertEquals(2, e1.getReadingOrder());
        assertTrue(changed, "내용이 같아도 순서가 바뀌면 저장 대상");
    }

    @Test
    void 아무것도_안_바뀌면_false를_돌려준다() {
        when(textRepo.findByPageResult(any())).thenReturn(List.of(textEl("e1", 1, "그대로")));

        assertFalse(saver.savePage(job, page(List.of(item("e1", "그대로")), null)),
                "카드 날짜를 건드리지 않게 하려면 변경 여부를 알려줘야 한다");
    }

    @Test
    void 모르는_id는_404_중복_id는_400() {
        when(textRepo.findByPageResult(any())).thenReturn(List.of(textEl("e1", 1, "가")));

        assertEquals(ErrorCode.ELEMENT_NOT_FOUND, assertThrows(CustomException.class,
                () -> saver.savePage(job, page(List.of(item("없는id", "x")), null))).getErrorCode());

        assertEquals(ErrorCode.ELEMENT_LIST_MISMATCH, assertThrows(CustomException.class,
                () -> saver.savePage(job, page(List.of(item("e1", "가"), item("e1", "가")), null))).getErrorCode());
    }

    /** FE가 두 패널을 완성해 보낸다 — 텍스트를 고쳐도 점자가 따라 바뀌지 않는다 */
    @Test
    void 두_패널을_받은_그대로_쓴다() {
        TextElement t1 = textEl("e1", 1, "원본");
        BrailleElement b1 = brailleEl("e1", 1, "⠏⠒⠃⠷");
        when(textRepo.findByPageResult(any())).thenReturn(List.of(t1));
        when(brailleRepo.findByPageResult(any())).thenReturn(List.of(b1));

        saver.savePage(job, page(List.of(item("e1", "수정")), List.of(item("e1", "⠠⠍⠨⠎⠶"))));

        assertEquals(List.of("수정"), t1.getCurrentContents());
        assertEquals(List.of("⠠⠍⠨⠎⠶"), b1.getCurrentContent(), "FE가 보낸 점자를 그대로");
        assertEquals(List.of("⠏⠒⠃⠷"), b1.getOriginalContent(), "AI 원본은 보존");
    }

    @Test
    void 점자_패널만_보내면_텍스트는_건드리지_않는다() {
        TextElement t1 = textEl("e1", 1, "원본");
        BrailleElement b1 = brailleEl("e1", 1, "⠏⠒");
        when(textRepo.findByPageResult(any())).thenReturn(List.of(t1));
        when(brailleRepo.findByPageResult(any())).thenReturn(List.of(b1));

        saver.savePage(job, page(null, List.of(item("e1", "⠠⠍"))));

        assertEquals(List.of("원본"), t1.getCurrentContents(), "역점역은 지원하지 않는다");
        assertEquals(List.of("⠠⠍"), b1.getCurrentContent());
    }

    // ── 대체 초안 선택 ───────────────────────────────────────────────
    // 본문은 FE가 그 초안으로 채워 보낸다 — 서버는 "어느 것을 골랐나"만 기록한다.
    // 조회 응답의 selected_idx가 이 값이라, 저장하지 않으면 화면이 다음에 엉뚱한 초안을 짚는다.

    @Test
    void 고른_초안_번호를_기록한다() {
        BrailleElement b1 = brailleEl("v1", 1, "⠈⠪ 기존");
        b1.updateSelectedIdx(2);
        when(brailleRepo.findByPageResult(any())).thenReturn(List.of(b1));

        boolean changed = saver.savePage(job, page(null, List.of(draftPick("v1", "⠠⠄생략⠠⠄", 0))));

        assertEquals(0, b1.getSelectedIdx());
        assertEquals(List.of("⠠⠄생략⠠⠄"), b1.getCurrentContent(), "본문은 FE가 보낸 그대로");
        assertTrue(changed);
    }

    @Test
    void 선택_해제는_음수1로_온다() {
        BrailleElement b1 = brailleEl("v1", 1, "⠠⠄생략⠠⠄");
        b1.updateSelectedIdx(0);
        when(brailleRepo.findByPageResult(any())).thenReturn(List.of(b1));

        saver.savePage(job, page(null, List.of(draftPick("v1", "⠈⠪ 원본", -1))));

        assertEquals(-1, b1.getSelectedIdx(), "AI 원본으로 되돌림");
    }

    @Test
    void 초안_번호를_안_보내면_그대로_둔다() {
        BrailleElement b1 = brailleEl("v1", 1, "⠈⠪ 기존");
        b1.updateSelectedIdx(2);
        when(brailleRepo.findByPageResult(any())).thenReturn(List.of(b1));

        saver.savePage(job, page(null, List.of(item("v1", "⠈⠪ 기존"))));

        assertEquals(2, b1.getSelectedIdx(), "null은 미전송 — 건드리지 않는다");
    }

    // ── 줄 메모 ─────────────────────────────────────────────────────

    private JobRequestDto.SaveMemo memo(String panel, String elementId, int line, String body) {
        return new JobRequestDto.SaveMemo(null, panel, elementId, line, "이 시기 도시 인구는", body, null,
                null, null, null, null);
    }

    @Test
    void 메모는_쪽_단위로_통째로_갈아끼운다() {
        saver.savePage(job, new JobRequestDto.SaveJobPage(1, null, null,
                List.of(memo("TEXT", "e1", 2, "원문 확인 필요"))));

        verify(memoRepo).deleteByPageId(any());
        ArgumentCaptor<PageMemo> captor = ArgumentCaptor.forClass(PageMemo.class);
        verify(memoRepo).save(captor.capture());
        PageMemo m = captor.getValue();
        assertEquals("TEXT", m.getPanel());
        assertEquals("e1", m.getElementId());
        assertEquals(2, m.getLineIndex());
        assertEquals("원문 확인 필요", m.getBody(), "메모 본문");
        assertEquals("이 시기 도시 인구는", m.getLineText(), "그 줄의 원문 — 메모가 아니다");
    }

    @Test
    void 메모가_원래도_없고_보낸_것도_없으면_건드리지_않는다() {
        saver.savePage(job, new JobRequestDto.SaveJobPage(1, null, null, List.of()));

        verify(memoRepo, never()).deleteByPageId(any());
    }

    @Test
    void 모르는_패널은_400() {
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, assertThrows(CustomException.class,
                () -> saver.savePage(job, new JobRequestDto.SaveJobPage(1, null, null,
                        List.of(memo("ORIGINAL", "e1", 0, "x"))))).getErrorCode());
    }

    // ── 메모 작성자 · 해결 · 답글 (V42) ──────────────────────────────
    // 협업은 .smj 파일 전달이다 — 받은 사람이 단 답글이 원래 주인의 저장에 함께 올라온다

    private JobRequestDto.SaveMemo threaded(String author, Boolean resolved, java.time.LocalDateTime resolvedAt,
                                            List<JobRequestDto.SaveMemoReply> replies) {
        return new JobRequestDto.SaveMemo(null, "TEXT", "e1", 2, "이 시기 도시 인구는", "원문이 「작다」인데",
                null, author, resolved, resolvedAt, replies);
    }

    /** 작성자는 앱이 보낸 이름 그대로 — 저장하는 사람으로 덮어쓰면 남의 답글이 내 것이 된다 */
    @Test
    void 작성자와_답글을_받은_그대로_저장한다() {
        java.time.LocalDateTime at = java.time.LocalDateTime.of(2026, 10, 20, 9, 30);
        saver.savePage(job, new JobRequestDto.SaveJobPage(1, null, null, List.of(threaded("김점역", false, null, List.of(
                new JobRequestDto.SaveMemoReply("이교정", "확인했습니다. 출판사에 알려 두겠습니다.", at),
                new JobRequestDto.SaveMemoReply(null, "다음 판에서 고칩니다.", null))))));

        ArgumentCaptor<PageMemo> memo = ArgumentCaptor.forClass(PageMemo.class);
        verify(memoRepo).save(memo.capture());
        assertEquals("김점역", memo.getValue().getAuthor());
        assertFalse(memo.getValue().isResolved());
        assertNull(memo.getValue().getResolvedAt(), "미해결이면 해결 시각이 없다");

        ArgumentCaptor<com.semojum.backend.domain.result.entity.PageMemoReply> reply =
                ArgumentCaptor.forClass(com.semojum.backend.domain.result.entity.PageMemoReply.class);
        verify(replyRepo, times(2)).save(reply.capture());
        var first = reply.getAllValues().get(0);
        assertEquals(501L, first.getMemoId(), "답글은 방금 저장한 메모에 붙는다");
        assertEquals("이교정", first.getAuthor());
        assertEquals(at, first.getCreatedAt(), "파일에 든 작성 시각 보존");
        assertNull(reply.getAllValues().get(1).getAuthor(), "작성자가 없으면 비워 둔다");
    }

    @Test
    void 해결로_오면_시각을_남기고_없으면_지금으로_채운다() {
        java.time.LocalDateTime at = java.time.LocalDateTime.of(2026, 10, 21, 11, 0);
        saver.savePage(job, new JobRequestDto.SaveJobPage(1, null, null, List.of(
                threaded("김점역", true, at, null), threaded("김점역", true, null, null))));

        ArgumentCaptor<PageMemo> memo = ArgumentCaptor.forClass(PageMemo.class);
        verify(memoRepo, times(2)).save(memo.capture());
        assertTrue(memo.getAllValues().get(0).isResolved());
        assertEquals(at, memo.getAllValues().get(0).getResolvedAt());
        assertNotNull(memo.getAllValues().get(1).getResolvedAt());
    }

    /** 미해결로 돌리면 예전 해결 시각이 남지 않는다 */
    @Test
    void 미해결이면_보낸_해결_시각을_버린다() {
        saver.savePage(job, new JobRequestDto.SaveJobPage(1, null, null, List.of(
                threaded("김점역", false, java.time.LocalDateTime.of(2026, 10, 21, 11, 0), null))));

        ArgumentCaptor<PageMemo> memo = ArgumentCaptor.forClass(PageMemo.class);
        verify(memoRepo).save(memo.capture());
        assertNull(memo.getValue().getResolvedAt());
    }

    /** 통째 교체라 기존 메모의 답글도 지운다 — 남기면 가리킬 메모가 없는 답글이 쌓인다 */
    @Test
    void 메모를_갈아끼울_때_기존_답글도_지운다() {
        PageMemo old = PageMemo.builder().pageId(pageId).panel("TEXT").elementId("e1").lineIndex(0).body("옛 메모").build();
        org.springframework.test.util.ReflectionTestUtils.setField(old, "id", 77L);
        when(memoRepo.findByPageIdOrderByElementIdAscLineIndexAsc(any())).thenReturn(List.of(old));

        saver.savePage(job, new JobRequestDto.SaveJobPage(1, null, null, List.of()));

        verify(replyRepo).deleteByMemoIdIn(List.of(77L));
        verify(memoRepo).deleteByPageId(any());
    }

    @Test
    void 빈_답글이나_너무_긴_작성자는_400() {
        for (JobRequestDto.SaveMemo bad : List.of(
                threaded("김점역", false, null, List.of(new JobRequestDto.SaveMemoReply("이교정", " ", null))),
                threaded("가".repeat(101), false, null, null))) {
            assertEquals(ErrorCode.COMMON_BAD_REQUEST, assertThrows(CustomException.class,
                    () -> saver.savePage(job, new JobRequestDto.SaveJobPage(1, null, null, List.of(bad)))).getErrorCode());
        }
    }

    // ── 텍스트 작업의 첫 저장 (V3.1) ─────────────────────────────────
    // 텍스트 작업은 AI를 거치지 않아 생성 때 쪽이 없다. 첫 저장이 앱이 보낸 쪽 번호대로 만든다.

    private JobRequestDto.SaveJobPage textPage(int pageNo) {
        return new JobRequestDto.SaveJobPage(pageNo,
                List.of(new JobRequestDto.SaveElement(null, List.of("<!2칸>사회는 변한다."), null)),
                List.of(new JobRequestDto.SaveElement(null, List.of("⠇⠚⠉⠵"), null)),
                null);
    }

    @Test
    void 텍스트_작업은_없는_쪽을_첫_저장에서_만든다() {
        Job textJob = Job.builder().id("job-t").mode("b").totalPages(0).originalFileName("무제").build();
        when(pageResultRepo.findByJobIdAndPageNumber(anyString(), anyInt())).thenReturn(Optional.empty());
        when(pageRepo.findByJobAndPageNo(any(), anyInt())).thenReturn(Optional.empty());
        when(pageRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(pageResultRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        saver.savePage(textJob, textPage(3));

        ArgumentCaptor<Page> page = ArgumentCaptor.forClass(Page.class);
        verify(pageRepo).save(page.capture());
        assertEquals(3, page.getValue().getPageNo(), "앱이 보낸 쪽 번호 그대로");
        assertEquals("COMPLETED", page.getValue().getStatus());

        ArgumentCaptor<PageResult> result = ArgumentCaptor.forClass(PageResult.class);
        verify(pageResultRepo).save(result.capture());
        assertEquals(3, result.getValue().getPageNumber());
        verify(textRepo).save(any(TextElement.class));
        verify(brailleRepo).save(any(BrailleElement.class));
    }

    /**
     * 운영 통계는 쪽 결과의 COMPLETED·NEEDS_REVIEW를 변환 쪽수로 센다.
     * AI가 돌지 않은 쪽을 거기 넣으면 변환량·쪽당 원가가 조용히 틀어진다.
     */
    @Test
    void 저장으로_생긴_쪽_결과는_변환_쪽수로_세지_않는_상태다() {
        Job textJob = Job.builder().id("job-t").mode("b").totalPages(0).originalFileName("무제").build();
        when(pageResultRepo.findByJobIdAndPageNumber(anyString(), anyInt())).thenReturn(Optional.empty());
        when(pageRepo.findByJobAndPageNo(any(), anyInt())).thenReturn(Optional.empty());
        when(pageRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(pageResultRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        saver.savePage(textJob, textPage(1));

        ArgumentCaptor<PageResult> result = ArgumentCaptor.forClass(PageResult.class);
        verify(pageResultRepo).save(result.capture());
        assertEquals(PageContentSaver.STATUS_SAVED, result.getValue().getStatus());
        assertFalse(List.of("COMPLETED", "NEEDS_REVIEW").contains(result.getValue().getStatus()));
    }

    /** PDF 작업의 쪽은 변환이 만든다 — 없는 쪽 번호는 앱 버그라 그대로 드러낸다 */
    @Test
    void PDF_작업은_없는_쪽을_만들지_않고_JOB4001이다() {
        when(pageResultRepo.findByJobIdAndPageNumber(anyString(), anyInt())).thenReturn(Optional.empty());

        CustomException e = assertThrows(CustomException.class, () -> saver.savePage(job, textPage(9)));
        assertEquals(ErrorCode.JOB_NOT_FOUND, e.getErrorCode());
        verify(pageRepo, never()).save(any());
    }

    @Test
    void 쪽_번호가_0이하면_400이다() {
        Job textJob = Job.builder().id("job-t").mode("b").totalPages(0).originalFileName("무제").build();
        when(pageResultRepo.findByJobIdAndPageNumber(anyString(), anyInt())).thenReturn(Optional.empty());

        CustomException e = assertThrows(CustomException.class, () -> saver.savePage(textJob, textPage(0)));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }
}

package com.semojum.backend.domain.result.service;

import com.semojum.backend.domain.job.dto.JobRequestDto;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.result.entity.*;
import com.semojum.backend.domain.result.repository.*;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.s3.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 페이지 일괄 저장 — 서버 diff 판정(수정/추가/삭제/순서)과 page_edit_logs 스냅샷 검증 */
class PageSaveServiceTest {

    JobRepository jobRepo;
    PageRepository pageRepo;
    PageResultRepository pageResultRepo;
    TextElementRepository textRepo;
    BrailleElementRepository brailleRepo;
    BoundingBoxRepository boxRepo;
    PageEditLogRepository logRepo;
    S3Service s3;
    BrailleRetranslator retranslator;
    PageSaveService service;

    final String USER_ID = UUID.randomUUID().toString();
    Job job;
    PageResult pageResult;

    @BeforeEach
    void setUp() {
        jobRepo = Mockito.mock(JobRepository.class);
        pageRepo = Mockito.mock(PageRepository.class);
        pageResultRepo = Mockito.mock(PageResultRepository.class);
        textRepo = Mockito.mock(TextElementRepository.class);
        brailleRepo = Mockito.mock(BrailleElementRepository.class);
        boxRepo = Mockito.mock(BoundingBoxRepository.class);
        logRepo = Mockito.mock(PageEditLogRepository.class);
        s3 = Mockito.mock(S3Service.class);
        retranslator = Mockito.mock(BrailleRetranslator.class);
        service = new PageSaveService(jobRepo, pageRepo, pageResultRepo, textRepo, brailleRepo,
                boxRepo, logRepo, s3, retranslator);

        when(textRepo.findByPageResult(any())).thenReturn(List.of());
        when(brailleRepo.findByPageResult(any())).thenReturn(List.of());
        when(boxRepo.findByPageResult(any())).thenReturn(List.of());
        when(textRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(brailleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pageRepo.findByJobAndPageNo(any(), anyInt())).thenAnswer(inv ->
                java.util.Optional.of(Page.builder().job(job).pageNo(1).pdfPath("s3/page-1.pdf").build()));
    }

    private void givenJob(String mode) {
        job = Job.builder().id("job1").mode(mode).totalPages(1).originalFileName("f").build();
        pageResult = PageResult.builder().pageNumber(1).mode(mode).status("COMPLETED")
                .imageWidth("b".equals(mode) ? null : 1224)
                .imageHeight("b".equals(mode) ? null : 1584)
                .build();
        when(jobRepo.findByIdAndUserId(anyString(), any())).thenReturn(java.util.Optional.of(job));
        when(pageResultRepo.findByJobIdAndPageNumber(anyString(), anyInt()))
                .thenReturn(java.util.Optional.of(pageResult));
    }

    private TextElement textEl(String elementId, String contents) {
        return TextElement.builder().pageResult(pageResult).elementId(elementId)
                .type("text").readingOrder(1).contents(List.of(contents)).isBlocked(false).build();
    }

    private JobRequestDto.SaveElement item(String id, String contents) {
        return new JobRequestDto.SaveElement(id, List.of(contents));
    }

    private PageEditLog savedLog() {
        ArgumentCaptor<PageEditLog> captor = ArgumentCaptor.forClass(PageEditLog.class);
        verify(logRepo).save(captor.capture());
        return captor.getValue();
    }

    /** 내용이 바뀐 요소만 edited로 판정하고, before/after 스냅샷이 정확해야 한다 */
    @Test
    void 내용_수정은_바뀐_요소만_edited로_기록된다() {
        givenJob("a");
        when(textRepo.findByPageResult(any())).thenReturn(List.of(textEl("e1", "원본1"), textEl("e2", "원본2")));

        List<Map<String, Object>> result = service.savePage(USER_ID, "job1", 1,
                List.of(item("e1", "수정1"), item("e2", "원본2")));

        PageEditLog log = savedLog();
        assertEquals(List.of("e1"), ((Map<?, ?>) log.getChanged()).get("edited"));
        assertEquals(List.of(), ((Map<?, ?>) log.getChanged()).get("added"));
        assertEquals(List.of("원본1"), log.getBeforeElements().get(0).get("contents"));
        assertEquals(List.of("수정1"), log.getAfterElements().get(0).get("contents"));
        assertEquals(List.of("수정1"), result.get(0).get("contents"));
        assertTrue(job.isEdited(), "내용이 바뀌었으므로 카드 날짜 갱신");
    }

    /** id 없는 항목은 새 블록 — 서버가 id 발급, origin=user, ai_original=null */
    @Test
    void 블록_추가는_서버가_id를_발급하고_사용자_작성으로_표시한다() {
        givenJob("a");
        when(textRepo.findByPageResult(any())).thenReturn(List.of(textEl("e1", "기존")));

        List<Map<String, Object>> result = service.savePage(USER_ID, "job1", 1,
                List.of(item("e1", "기존"), item(null, "새 블록")));

        String newId = (String) result.get(1).get("id");
        assertNotNull(newId, "새 블록엔 발급된 id");
        PageEditLog log = savedLog();
        assertEquals(List.of(newId), ((Map<?, ?>) log.getChanged()).get("added"));
        assertEquals(1, log.getBeforeElements().size(), "before엔 추가 블록 없음");
        Map<String, Object> addedSnap = log.getAfterElements().get(1);
        assertEquals("user", addedSnap.get("origin"));
        assertNull(addedSnap.get("ai_original"));
        assertEquals("text", addedSnap.get("type"), "사용자 블록은 항상 text");
    }

    /** 배열에서 빠진 기존 요소는 삭제(soft-delete)로 판정된다 */
    @Test
    void 배열에서_빠진_요소는_삭제된다() {
        givenJob("a");
        TextElement e1 = textEl("e1", "남김");
        TextElement e2 = textEl("e2", "지움");
        when(textRepo.findByPageResult(any())).thenReturn(List.of(e1, e2));

        service.savePage(USER_ID, "job1", 1, List.of(item("e1", "남김")));

        assertTrue(e2.isDeleted());
        assertFalse(e1.isDeleted());
        assertEquals(List.of("e2"), ((Map<?, ?>) savedLog().getChanged()).get("deleted"));
    }

    /** 내용이 같아도 순서가 바뀌면 저장 대상 — reading_order 재번호 + reordered=true */
    @Test
    void 순서만_바뀌어도_저장되고_재번호된다() {
        givenJob("a");
        TextElement e1 = textEl("e1", "가");
        TextElement e2 = textEl("e2", "나");
        when(textRepo.findByPageResult(any())).thenReturn(List.of(e1, e2));

        service.savePage(USER_ID, "job1", 1, List.of(item("e2", "나"), item("e1", "가")));

        assertEquals(true, ((Map<?, ?>) savedLog().getChanged()).get("reordered"));
        assertEquals(1, e2.getReadingOrder());
        assertEquals(2, e1.getReadingOrder());
    }

    /** 아무것도 안 바뀐 저장은 로그·카드 날짜를 남기지 않는다 */
    @Test
    void 변경_없는_저장은_로그를_남기지_않는다() {
        givenJob("a");
        when(textRepo.findByPageResult(any())).thenReturn(List.of(textEl("e1", "그대로")));

        service.savePage(USER_ID, "job1", 1, List.of(item("e1", "그대로")));

        verify(logRepo, never()).save(any());
        assertFalse(job.isEdited());
    }

    /** FE 화면 상태가 DB와 어긋난 요청은 거절 — 모르는 id는 404, 중복 id는 400 */
    @Test
    void 모르는_id는_404_중복_id는_400() {
        givenJob("a");
        when(textRepo.findByPageResult(any())).thenReturn(List.of(textEl("e1", "가")));

        CustomException notFound = assertThrows(CustomException.class, () ->
                service.savePage(USER_ID, "job1", 1, List.of(item("없는id", "x"))));
        assertEquals(ErrorCode.ELEMENT_NOT_FOUND, notFound.getErrorCode());

        CustomException dup = assertThrows(CustomException.class, () ->
                service.savePage(USER_ID, "job1", 1, List.of(item("e1", "가"), item("e1", "가"))));
        assertEquals(ErrorCode.ELEMENT_LIST_MISMATCH, dup.getErrorCode());
    }

    /** mode b는 braille_elements가 편집 대상이고, 로그에 원본 한글 텍스트가 담긴다 */
    @Test
    void mode_b는_braille이_대상이고_원문_텍스트를_로그에_담는다() {
        givenJob("b");
        BrailleElement b1 = BrailleElement.builder().pageResult(pageResult).elementId("b1")
                .type("text").readingOrder(1).content(List.of("⠁⠝")).isBlocked(false).build();
        when(brailleRepo.findByPageResult(any())).thenReturn(List.of(b1));
        when(s3.downloadFile(anyString())).thenReturn("원본 한글".getBytes(StandardCharsets.UTF_8));

        service.savePage(USER_ID, "job1", 1, List.of(item("b1", "⠁⠝⠞")));

        PageEditLog log = savedLog();
        assertEquals("BRAILLE", log.getElementType());
        assertEquals("원본 한글", log.getSourceText());
        assertNull(log.getSourcePdfPath(), "mode b엔 PDF 경로 없음");
        assertEquals(List.of("⠁⠝⠞"), b1.getCurrentContent());
        verify(textRepo, never()).findByPageResult(any());
    }

    private static Map<String, Object> draft(String label, String text, List<String> contents) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("text", text);
        m.put("contents", contents);
        m.put("label", label);
        return m;
    }

    /** mode b·c는 결과물이 점자라 초안의 contents를 그대로 본문에 넣는다 */
    @Test
    void 초안_선택은_본문과_selected_idx를_함께_바꾼다() {
        givenJob("c");
        BrailleElement el = BrailleElement.builder().pageResult(pageResult).elementId("v1")
                .type("chart_graph").readingOrder(1).content(List.of("⠈⠪ 기존"))
                .selectedIdx(2)
                .drafts(List.of(draft("생략", "그래프 생략", List.of("⠠⠄생략⠠⠄")),
                                draft("개조식 설명", "그래프: …", List.of("⠠⠄개조식⠠⠄"))))
                .isBlocked(false).build();
        when(brailleRepo.findByPageResult(any())).thenReturn(List.of(el));

        Map<String, Object> result = service.selectDraft(USER_ID, "job1", 1, "v1", 0);

        assertEquals(List.of("⠠⠄생략⠠⠄"), result.get("contents"));
        assertEquals(0, result.get("selectedIdx"));
        assertEquals(List.of("⠠⠄생략⠠⠄"), el.getCurrentContent(), "본문 교체");
        assertEquals(0, el.getSelectedIdx());
        assertTrue(job.isEdited(), "내용이 바뀌었으므로 카드 날짜 갱신");

        Map<?, ?> changed = (Map<?, ?>) savedLog().getChanged();
        Map<?, ?> sel = (Map<?, ?>) ((List<?>) changed.get("draft_selected")).get(0);
        assertEquals("v1", sel.get("element_id"));
        assertEquals(2, sel.get("from"));
        assertEquals(0, sel.get("to"));
        assertEquals("생략", sel.get("label"), "어느 초안을 골랐는지가 RLHF 신호");
    }

    /** mode a는 결과물이 텍스트라 초안 contents가 비어 있다 → text를 쓰고 점역자주 마커 형태를 보존한다 */
    @Test
    void mode_a는_초안_text를_쓰고_점역자주_마커를_보존한다() {
        givenJob("a");
        TextElement el = TextElement.builder().pageResult(pageResult).elementId("v1")
                .type("chart_graph").readingOrder(1)
                .contents(List.of("<!점역자주>기존 설명<!/점역자주>"))
                .selectedIdx(0)
                .drafts(List.of(draft("줄글 설명", "새 설명", List.of())))
                .isBlocked(false).build();
        when(textRepo.findByPageResult(any())).thenReturn(List.of(el));

        service.selectDraft(USER_ID, "job1", 1, "v1", 0);

        assertEquals(List.of("<!점역자주>새 설명<!/점역자주>"), el.getCurrentContents(),
                "점자가 없으므로 text를 쓰되 마커는 그대로 감싼다");
    }

    /** -1은 선택 해제 — AI 원본(original)으로 되돌린다 */
    @Test
    void selectedIdx_음수1은_AI_원본으로_되돌린다() {
        givenJob("c");
        BrailleElement el = BrailleElement.builder().pageResult(pageResult).elementId("v1")
                .type("chart_graph").readingOrder(1).content(List.of("⠠⠄AI 원본⠠⠄"))
                .selectedIdx(2)
                .drafts(List.of(draft("생략", "그래프 생략", List.of("⠠⠄생략⠠⠄"))))
                .isBlocked(false).build();
        el.updateCurrentContent(List.of("⠠⠄사용자가 고른 초안⠠⠄")); // original은 보존됨
        when(brailleRepo.findByPageResult(any())).thenReturn(List.of(el));

        Map<String, Object> result = service.selectDraft(USER_ID, "job1", 1, "v1", -1);

        assertEquals(List.of("⠠⠄AI 원본⠠⠄"), el.getCurrentContent(), "original로 복귀");
        assertEquals(-1, el.getSelectedIdx());
        assertEquals(-1, result.get("selectedIdx"));
    }

    /** 초안이 없는 요소, 범위 밖 번호는 400 */
    @Test
    void 초안_없거나_범위_밖이면_400() {
        givenJob("c");
        BrailleElement noDraft = BrailleElement.builder().pageResult(pageResult).elementId("t1")
                .type("text").readingOrder(1).content(List.of("⠈⠪")).isBlocked(false).build();
        BrailleElement withDraft = BrailleElement.builder().pageResult(pageResult).elementId("v1")
                .type("chart_graph").readingOrder(2).content(List.of("⠈⠪"))
                .drafts(List.of(draft("생략", "그래프 생략", List.of("⠠⠄생략⠠⠄"))))
                .isBlocked(false).build();
        when(brailleRepo.findByPageResult(any())).thenReturn(List.of(noDraft, withDraft));

        assertEquals(ErrorCode.COMMON_BAD_REQUEST,
                assertThrows(CustomException.class,
                        () -> service.selectDraft(USER_ID, "job1", 1, "t1", 0)).getErrorCode(),
                "초안 없는 요소");
        assertEquals(ErrorCode.COMMON_BAD_REQUEST,
                assertThrows(CustomException.class,
                        () -> service.selectDraft(USER_ID, "job1", 1, "v1", 5)).getErrorCode(),
                "범위 밖 번호");
        assertEquals(ErrorCode.ELEMENT_NOT_FOUND,
                assertThrows(CustomException.class,
                        () -> service.selectDraft(USER_ID, "job1", 1, "없는id", 0)).getErrorCode());
        verify(logRepo, never()).save(any());
    }

    /** mode a/c 스냅샷엔 요소별 bounding_box가 포함된다 (자기완결 학습 데이터) */
    @Test
    void mode_a_스냅샷엔_bbox와_이미지_크기가_담긴다() {
        givenJob("a");
        when(textRepo.findByPageResult(any())).thenReturn(List.of(textEl("e1", "가")));
        when(boxRepo.findByPageResult(any())).thenReturn(List.of(
                BoundingBox.builder().pageResult(pageResult).elementId("e1")
                        .x(1).y(2).x2(3).y2(4).type("text").build()));

        service.savePage(USER_ID, "job1", 1, List.of(item("e1", "수정")));

        PageEditLog log = savedLog();
        assertEquals(1224, log.getImageWidth());
        Map<?, ?> bbox = (Map<?, ?>) log.getBeforeElements().get(0).get("bounding_box");
        assertEquals(1, bbox.get("x"));
        assertEquals(4, bbox.get("y2"));
    }
    // ===== 3패널(mode c) — 텍스트 편집이 점자를 끌고 간다 (2026-09-30) =====

    private BrailleElement brailleEl(String elementId, int order, String contents) {
        return BrailleElement.builder().pageResult(pageResult).elementId(elementId)
                .type("text").readingOrder(order).content(List.of(contents)).isBlocked(false).build();
    }

    private List<Map<String, Object>> saveText(List<JobRequestDto.SaveElement> items, Map<String, String> bodies) {
        return service.savePage(USER_ID, "job1", 1, "text", items, bodies);
    }

    /** 바뀐 텍스트만 재점역해 같은 id 점자의 current를 바꾸고, 이전 점자의 앞뒤 여백을 그대로 입힌다 */
    @Test
    void mode_c_텍스트_수정은_같은_id_점자를_재점역하고_여백을_보존한다() {
        givenJob("c");
        TextElement t1 = textEl("e1", "원본");
        TextElement t2 = textEl("e2", "그대로");
        BrailleElement b1 = brailleEl("e1", 1, "⠀⠀⠏⠒⠃⠷\n");
        BrailleElement b2 = brailleEl("e2", 2, "⠀⠀⠈⠪⠊⠗⠐⠥\n");
        when(textRepo.findByPageResult(any())).thenReturn(List.of(t1, t2));
        when(brailleRepo.findByPageResult(any())).thenReturn(List.of(b1, b2));

        List<Map<String, Object>> result = saveText(
                List.of(item("e1", "수정"), item("e2", "그대로")), Map.of("수정", "⠠⠍⠨⠎⠶"));

        assertEquals(List.of("수정"), t1.getCurrentContents());
        assertEquals(List.of("⠀⠀⠠⠍⠨⠎⠶\n"), b1.getCurrentContent(), "표식 없는 줄은 이전 점자 같은 줄의 들여쓰기(⠀⠀)");
        assertEquals(List.of("⠀⠀⠏⠒⠃⠷\n"), b1.getOriginalContent(), "AI 원본 보존");
        assertEquals(List.of("⠀⠀⠈⠪⠊⠗⠐⠥\n"), b2.getCurrentContent(), "안 바뀐 텍스트의 점자는 그대로");
        assertEquals(List.of("⠀⠀⠠⠍⠨⠎⠶\n"), result.get(0).get("brailleContents"), "응답에 바뀐 점자");
        verify(retranslator, never()).translateLines(any()); // 미리 구해 온 값을 쓴다

        PageEditLog log = savedLog();
        assertEquals("TEXT", log.getElementType());
        Map<?, ?> synced = (Map<?, ?>) ((Map<?, ?>) log.getChanged()).get("braille_synced");
        assertEquals(List.of("e1"), synced.get("edited"));
    }

    /** 새 텍스트 블록은 같은 id로 점자 블록을 만들고, 지운 텍스트는 짝 점자도 지운다 */
    @Test
    void mode_c_텍스트_추가_삭제는_짝_점자에도_반영된다() {
        givenJob("c");
        TextElement t1 = textEl("e1", "남김");
        TextElement t2 = textEl("e2", "지움");
        BrailleElement b1 = brailleEl("e1", 1, "  ⠁\n");
        BrailleElement b2 = brailleEl("e2", 2, "  ⠃\n");
        when(textRepo.findByPageResult(any())).thenReturn(List.of(t1, t2));
        when(brailleRepo.findByPageResult(any())).thenReturn(List.of(b1, b2));

        List<Map<String, Object>> result = saveText(
                List.of(item("e1", "남김"), item(null, "<!2칸>새 문단")), Map.of("<!2칸>새 문단", "⠀⠀⠠⠗⠀⠑⠛⠊⠒"));

        String newId = (String) result.get(1).get("id");
        ArgumentCaptor<BrailleElement> captor = ArgumentCaptor.forClass(BrailleElement.class);
        verify(brailleRepo).save(captor.capture());
        BrailleElement created = captor.getValue();
        assertEquals(newId, created.getElementId(), "텍스트와 같은 id — 1:1 매칭 유지");
        assertEquals(List.of("⠀⠀⠠⠗⠀⠑⠛⠊⠒\n"), created.getCurrentContent(), "새 블록: 표식 들여쓰기 + 뒤 줄바꿈");
        assertNull(created.getOriginalContent(), "사용자 작성 표식");
        assertTrue(b2.isDeleted(), "지운 텍스트의 점자도 삭제");
        assertFalse(b1.isDeleted());
        assertEquals(2, created.getReadingOrder());
    }

    /** 순서: 짝 점자는 텍스트 순서를 따르고, 점자 패널에서만 추가한 블록은 원래 앞 블록 뒤에 붙어 다닌다 */
    @Test
    void mode_c_텍스트_순서변경은_점자_순서를_끌고_가고_점자_전용_블록은_제자리를_지킨다() {
        givenJob("c");
        TextElement t1 = textEl("e1", "가");
        TextElement t2 = textEl("e2", "나");
        BrailleElement b1 = brailleEl("e1", 1, "⠁");
        BrailleElement only = brailleEl("u1", 2, "⠿"); // 점자 패널에서 추가한 블록(짝 텍스트 없음)
        BrailleElement b2 = brailleEl("e2", 3, "⠃");
        when(textRepo.findByPageResult(any())).thenReturn(List.of(t1, t2));
        when(brailleRepo.findByPageResult(any())).thenReturn(List.of(b1, only, b2));

        saveText(List.of(item("e2", "나"), item("e1", "가")), Map.of());

        assertEquals(1, b2.getReadingOrder());
        assertEquals(2, b1.getReadingOrder());
        assertEquals(3, only.getReadingOrder(), "e1 뒤에 붙어 이동");
        verifyNoInteractions(retranslator);
    }

    /** 미리 구한 번역이 없으면(사전 조회 뒤 내용이 또 바뀐 경우) 저장 중에 한 번 더 부른다 */
    @Test
    void mode_c_사전_번역이_없으면_저장_중에_재점역한다() {
        givenJob("c");
        TextElement t1 = textEl("e1", "원본");
        BrailleElement b1 = brailleEl("e1", 1, "⠁");
        when(textRepo.findByPageResult(any())).thenReturn(List.of(t1));
        when(brailleRepo.findByPageResult(any())).thenReturn(List.of(b1));
        when(retranslator.translateLines(any())).thenReturn(Map.of("다른 값", "⠙"));

        saveText(List.of(item("e1", "다른 값")), Map.of());

        assertEquals(List.of("⠙"), b1.getCurrentContent(), "이전 점자에 뒤 줄바꿈이 없었으니 없음");
        verify(retranslator).translateLines(List.of("다른 값"));
    }

    /** 대상을 안 주면 c의 기본은 점자 — 3패널 이전 클라이언트와 같은 동작이고 텍스트는 건드리지 않는다 */
    @Test
    void mode_c_대상_생략은_점자_편집이고_텍스트와_무관하다() {
        givenJob("c");
        BrailleElement b1 = brailleEl("e1", 1, "⠁");
        when(brailleRepo.findByPageResult(any())).thenReturn(List.of(b1));

        List<Map<String, Object>> result = service.savePage(USER_ID, "job1", 1, List.of(item("e1", "⠁⠃")));

        assertEquals(List.of("⠁⠃"), b1.getCurrentContent());
        assertFalse(result.get(0).containsKey("brailleContents"), "점자 저장엔 동기화 필드 없음");
        assertEquals("BRAILLE", savedLog().getElementType());
        verify(textRepo, never()).findByPageResult(any());
    }

    /** 편집할 수 없는 패널 — a의 점자(없음), b의 텍스트(원문 대조용), 모르는 값은 400 */
    @Test
    void 모드가_편집할_수_없는_패널은_400() {
        assertEquals("text", PageSaveService.resolveTarget("a", null));
        assertEquals("braille", PageSaveService.resolveTarget("b", null));
        assertEquals("braille", PageSaveService.resolveTarget("c", null));
        assertEquals("text", PageSaveService.resolveTarget("c", "text"));
        for (String[] bad : new String[][]{{"a", "braille"}, {"b", "text"}, {"c", "both"}}) {
            assertEquals(ErrorCode.COMMON_BAD_REQUEST, assertThrows(CustomException.class,
                    () -> PageSaveService.resolveTarget(bad[0], bad[1])).getErrorCode(), bad[0] + "/" + bad[1]);
        }
    }

    /** 사전 조회는 바뀐 텍스트와 새 블록만 — 그대로인 요소는 AI에 보내지 않는다 */
    @Test
    void 재점역_대상은_바뀐_텍스트와_새_블록뿐이다() {
        givenJob("c");
        when(textRepo.findByPageResult(any())).thenReturn(List.of(textEl("e1", "그대로"), textEl("e2", "원본")));

        var texts = service.textsToRetranslate(USER_ID, "job1", 1, "text",
                List.of(item("e1", "그대로"), item("e2", "수정"), item(null, "새 블록")));

        assertEquals(java.util.Set.of("수정", "새 블록"), texts);
        assertTrue(service.textsToRetranslate(USER_ID, "job1", 1, "braille",
                List.of(item("e1", "바뀜"))).isEmpty(), "점자 저장은 재점역 없음");
    }

    /** c의 초안 선택은 점자 본문 + 같은 id 텍스트를 함께 바꾼다(피커가 보여 준 묵자 = 초안 text) */
    @Test
    void mode_c_초안_선택은_텍스트_패널도_같은_초안으로_맞춘다() {
        givenJob("c");
        BrailleElement b = BrailleElement.builder().pageResult(pageResult).elementId("v1")
                .type("chart_graph").readingOrder(1).content(List.of("⠠⠄기존⠠⠄")).selectedIdx(1)
                .drafts(List.of(draft("생략", "그래프 생략", List.of("⠠⠄생략⠠⠄")),
                                draft("개조식 설명", "그래프: 기존", List.of("⠠⠄기존⠠⠄"))))
                .isBlocked(false).build();
        TextElement t = TextElement.builder().pageResult(pageResult).elementId("v1")
                .type("chart_graph").readingOrder(1).contents(List.of("<!점역자주>그래프: 기존<!/점역자주>"))
                .selectedIdx(1).isBlocked(false).build();
        when(brailleRepo.findByPageResult(any())).thenReturn(List.of(b));
        when(textRepo.findByPageResult(any())).thenReturn(List.of(t));

        Map<String, Object> result = service.selectDraft(USER_ID, "job1", 1, "v1", 0);

        assertEquals(List.of("⠠⠄생략⠠⠄"), b.getCurrentContent());
        assertEquals(List.of("<!점역자주>그래프 생략<!/점역자주>"), t.getCurrentContents(), "마커 형태 보존");
        assertEquals(0, t.getSelectedIdx());
        assertEquals(t.getCurrentContents(), result.get("textContents"));
        assertEquals(List.of("v1"), ((Map<?, ?>) savedLog().getChanged()).get("text_synced"));
    }
}

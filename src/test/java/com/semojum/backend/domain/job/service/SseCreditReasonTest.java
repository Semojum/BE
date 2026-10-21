package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.PageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** SSE에 「크레딧 부족」 사유를 싣는다 — page_done의 blocked_reason, job_done의 credit_exhausted (2026-10-21) */
class SseCreditReasonTest {

    PageRepository pageRepository;
    SseService sseService;
    Map<String, Object> event;

    @BeforeEach
    void setUp() {
        pageRepository = Mockito.mock(PageRepository.class);
        sseService = new SseService(null, null, null, null, null, null, null, pageRepository, null);
        event = new LinkedHashMap<>();
    }

    private Page page(String reason) {
        Page p = Page.builder().pageNo(2).pdfPath("x").build();
        p.block(reason);
        return p;
    }

    @Test
    void 크레딧_부족으로_막힌_쪽은_사유를_싣는다() {
        when(pageRepository.findByJob_IdAndPageNo("job-1", 2)).thenReturn(Optional.of(page(Page.BLOCKED_CREDIT_EXHAUSTED)));
        sseService.addBlockedReason(event, "job-1", 2);
        assertEquals("CREDIT_EXHAUSTED", event.get("blocked_reason"));
    }

    /** AI 오류 · 취소로 막힌 쪽은 사유가 없다 — 키를 넣지 않는다 */
    @Test
    void 사유가_없으면_키를_넣지_않는다() {
        when(pageRepository.findByJob_IdAndPageNo("job-1", 2)).thenReturn(Optional.of(page(null)));
        sseService.addBlockedReason(event, "job-1", 2);
        assertFalse(event.containsKey("blocked_reason"));
    }

    @Test
    void job_done에는_크레딧_부족_여부를_항상_싣는다() {
        when(pageRepository.existsByJob_IdAndBlockedReason("job-1", Page.BLOCKED_CREDIT_EXHAUSTED)).thenReturn(true);
        sseService.addCreditExhausted(event, "job-1");
        assertEquals(true, event.get("credit_exhausted"));

        Map<String, Object> other = new LinkedHashMap<>();
        sseService.addCreditExhausted(other, "job-2");
        assertEquals(false, other.get("credit_exhausted"));
    }
}

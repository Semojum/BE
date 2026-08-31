package com.semojum.backend.domain.job.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 조판 옵션의 기본값·역호환 — 안 보낸 값은 점자 도서 관행(32칸×26줄·홀수 면)으로 채워진다 */
class LayoutOptionsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 아무것도_안_보내면_점자도서_기본값으로_채워진다() {
        LayoutOptions o = new LayoutOptions(null, null, null, null, null, null, null, null, null, null, null)
                .withDefaults();

        assertEquals(32, o.cellsPerLine());
        assertEquals(26, o.linesPerPage());
        assertEquals("odd", o.pageNumberLine());
        assertEquals(0, o.coverPages());
        assertEquals(1, o.sourcePageStart());
        assertEquals(1, o.braillePageStart());
        assertTrue(o.showSourcePageNumber());
        assertTrue(o.showBraillePageNumber());
        assertEquals("center", o.footerAlign());
        assertEquals("all", o.editScope());
        assertFalse(o.advancedAi());
    }

    @Test
    void 보낸_값은_그대로_유지되고_빠진_값만_채워진다() {
        LayoutOptions o = new LayoutOptions(40, null, "every", 2, null, 5, false, null, "right", null, true)
                .withDefaults();

        assertEquals(40, o.cellsPerLine());
        assertEquals(26, o.linesPerPage());     // 안 보냄 → 기본값
        assertEquals("every", o.pageNumberLine());
        assertEquals(2, o.coverPages());
        assertEquals(5, o.braillePageStart());
        assertFalse(o.showSourcePageNumber());  // false를 보냈으면 false 유지 (기본값 true로 덮지 않는다)
        assertTrue(o.showBraillePageNumber());
        assertEquals("right", o.footerAlign());
        assertTrue(o.advancedAi());
    }

    /** 옵션 없이 만든 기존 작업 — 구 insert_page_number만 반영한다 */
    @Test
    void 기존_작업은_구_insertPageNumber를_페이지행으로_옮긴다() {
        assertEquals("odd", LayoutOptions.legacy(true).pageNumberLine());
        assertEquals("none", LayoutOptions.legacy(false).pageNumberLine());
        assertEquals(32, LayoutOptions.legacy(false).cellsPerLine());
    }

    /** FE가 먼저 새 항목을 보내도 BE가 죽지 않아야 한다(기획 확정 전이라 항목이 늘 수 있다) */
    @Test
    void 모르는_필드는_무시한다() throws Exception {
        String json = "{\"cellsPerLine\":40,\"어떤새항목\":\"값\",\"anotherNew\":true}";

        LayoutOptions o = mapper.readValue(json, LayoutOptions.class);

        assertEquals(40, o.cellsPerLine());
    }
}

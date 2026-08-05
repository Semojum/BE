package com.semojum.backend.global.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

// 마이페이지 카드 날짜 표기 규칙 (피그마 V3-05 기준)
class RelativeDateFormatterTest {

    // 기준 시각: 2026-08-05(수) 14:30
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 14, 30);

    private String at(LocalDateTime t) {
        return RelativeDateFormatter.format(t, NOW);
    }

    @Test
    void 당일이면_경과_시간으로_표시된다() {
        assertEquals("방금", at(NOW));
        assertEquals("방금", at(NOW.minusSeconds(30)));
        assertEquals("1분 전", at(NOW.minusMinutes(1)));
        assertEquals("59분 전", at(NOW.minusMinutes(59)));
        assertEquals("1시간 전", at(NOW.minusHours(1)));
        assertEquals("14시간 전", at(NOW.minusHours(14)));
    }

    @Test
    void 전날이면_어제로_표시된다() {
        assertEquals("어제", at(NOW.minusDays(1)));
        assertEquals("어제", at(LocalDateTime.of(2026, 8, 4, 0, 0)));
        assertEquals("어제", at(LocalDateTime.of(2026, 8, 4, 23, 59)));
    }

    @Test
    void 올해면_월과_일만_표시된다() {
        assertEquals("8. 3.", at(LocalDateTime.of(2026, 8, 3, 10, 0)));
        assertEquals("7. 28.", at(LocalDateTime.of(2026, 7, 28, 10, 0)));
        assertEquals("1. 1.", at(LocalDateTime.of(2026, 1, 1, 0, 0)));
    }

    @Test
    void 해가_다르면_연도까지_표시된다() {
        assertEquals("2025. 12. 3.", at(LocalDateTime.of(2025, 12, 3, 10, 0)));
        assertEquals("2025. 12. 31.", at(LocalDateTime.of(2025, 12, 31, 23, 59)));
        assertEquals("2024. 2. 29.", at(LocalDateTime.of(2024, 2, 29, 12, 0)));
    }

    @Test
    void 경계값은_날짜_기준으로_갈린다() {
        // 오늘 00:00과 어제 23:59는 1분 차이지만 날짜가 다르므로 표기가 갈린다
        assertEquals("14시간 전", at(LocalDateTime.of(2026, 8, 5, 0, 0)));
        assertEquals("어제", at(LocalDateTime.of(2026, 8, 4, 23, 59)));
        assertEquals("8. 3.", at(LocalDateTime.of(2026, 8, 3, 23, 59)));
    }

    @Test
    void null이면_null을_반환한다() {
        assertNull(RelativeDateFormatter.format(null, NOW));
    }

    @Test
    void 시계_오차로_미래_시각이_와도_방금으로_처리된다() {
        assertEquals("방금", at(NOW.plusMinutes(5)));
    }
}

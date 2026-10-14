package com.semojum.backend.domain.job.worker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 작업 값 → AI 요청 값 (proto V3.1).
 *
 * <p>AI의 mode는 {@code "a"} PDF·이미지 | {@code "b"} 글 둘뿐이다. BE는 PDF·HWP·이미지 작업을
 * {@code c}로 저장하므로 보낼 때 옮겨야 한다 — 안 옮기면 AI가 모르는 mode라 모든 쪽이 실패한다.
 */
class AiRequestMappingTest {

    /** 3패널 작업(PDF·HWP·이미지)은 AI에 a로 간다 — DB의 c는 그대로 둔다 */
    @Test
    void c_작업은_a로_보낸다() {
        assertEquals("a", PageWorker.aiModeOf("c"));
    }

    @Test
    void 글_작업은_b_그대로다() {
        assertEquals("b", PageWorker.aiModeOf("b"));
    }

    /** 2026-09-30 이전에 만든 a 작업 — PDF 입력이라 그대로 a */
    @Test
    void 구_a_작업은_a다() {
        assertEquals("a", PageWorker.aiModeOf("a"));
    }

    /** 구 태스크엔 점역 옵션 칸이 없다 — 0이 AI 서버 기본값(약자)이다 */
    @Test
    void 숫자_칸이_없으면_0이다() {
        assertEquals(0, PageWorker.intOf(null));
        assertEquals(0, PageWorker.intOf("2"));
        assertEquals(2, PageWorker.intOf(2));
    }
}

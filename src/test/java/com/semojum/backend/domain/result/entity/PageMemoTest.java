package com.semojum.backend.domain.result.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 줄 메모 엔티티 — 앵커 보조 정보(line_text)와 작성 시각 처리.
 *
 * <p>메모 본문(body)과 줄 원문(lineText)은 다른 것이다. lineText는 "이 메모가 어느 줄에 붙었는지"를
 * 나중에 다시 찾기 위한 단서일 뿐이라, 길면 잘라도 된다 — 길이 때문에 저장이 실패하면 안 된다.
 */
class PageMemoTest {

    private PageMemo memo(String lineText, LocalDateTime createdAt) {
        return PageMemo.builder()
                .pageId(UUID.randomUUID())
                .panel(PageMemo.PANEL_TEXT)
                .elementId("t7")
                .lineIndex(2)
                .lineText(lineText)
                .body("원문이 「작다」인데 그래프를 보면 「크다」가 맞습니다")
                .createdAt(createdAt)
                .build();
    }

    @Test
    void 줄_원문이_상한을_넘으면_잘라_담는다() {
        String long_ = "가".repeat(PageMemo.LINE_TEXT_MAX + 50);

        PageMemo m = memo(long_, null);

        assertEquals(PageMemo.LINE_TEXT_MAX, m.getLineText().length(),
                "단서라 앞부분만 있으면 된다 — 길이로 저장이 깨지면 안 된다");
        assertTrue(long_.startsWith(m.getLineText()));
    }

    @Test
    void 줄_원문은_없어도_된다() {
        assertNull(memo(null, null).getLineText(), "없으면 lineIndex만으로 찾는다");
    }

    /** FE가 .smj에 들고 있던 작성 시각을 보내면 그대로 쓴다 — 저장할 때마다 새로 찍히면 안 된다 */
    @Test
    void 보내온_작성_시각을_보존한다() {
        LocalDateTime written = LocalDateTime.of(2026, 10, 11, 14, 2);

        PageMemo m = memo("이 시기 도시 인구는 크게 줄었다.", written);

        assertEquals(written, m.getCreatedAt());
        assertTrue(m.getUpdatedAt().isAfter(written), "갱신 시각은 지금");
    }

    @Test
    void 작성_시각이_없으면_지금으로_채운다() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        PageMemo m = memo("줄 원문", null);

        assertTrue(m.getCreatedAt().isAfter(before));
    }

    @Test
    void 메모_본문과_줄_원문은_다른_값이다() {
        PageMemo m = memo("이 시기 도시 인구는 크게 줄었다.", null);

        assertEquals("이 시기 도시 인구는 크게 줄었다.", m.getLineText(), "그 줄에 적혀 있던 글");
        assertEquals("원문이 「작다」인데 그래프를 보면 「크다」가 맞습니다", m.getBody(), "사용자가 쓴 메모");
    }
}

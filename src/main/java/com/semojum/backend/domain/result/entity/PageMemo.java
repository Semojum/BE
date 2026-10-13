package com.semojum.backend.domain.result.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 줄 메모 — 점역사가 텍스트·점자 각 줄에 남기는 메모 (V34).
 *
 * <p><b>앵커는 블록 + 블록 안 줄 번호</b>다. 면 기준 줄 번호를 쓰면 줄당 칸 수나 페이지당 줄 수를
 * 바꾸는 순간 면이 다시 나뉘어 메모가 전부 밀린다. 블록 안 줄은 조판과 무관해 살아남는다.
 *
 * <p>{@link #lineText}는 그 줄의 원문 앞부분이다 — 메모 내용이 아니다. 긴 줄이 쪼개져
 * {@link #lineIndex}가 어긋났을 때 FE가 이것으로 다시 찾는다.
 *
 * <p>쓰기는 작업 저장 본문에만 있다(전용 API 없음). 쪽 단위로 통째로 갈아끼운다.
 */
@Entity
@Table(name = "page_memos")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PageMemo {

    public static final String PANEL_TEXT = "TEXT";
    public static final String PANEL_BRAILLE = "BRAILLE";

    /** line_text 상한 — 넘으면 자른다. 재탐색 단서라 앞부분만 있으면 된다 */
    public static final int LINE_TEXT_MAX = 80;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 쪽 번호가 아니라 pages.id — 번호는 표시값이라 키로 쓸 만큼 안정적이지 않다 */
    @Column(name = "page_id", nullable = false, columnDefinition = "uuid")
    private UUID pageId;

    /** TEXT(.txt) · BRAILLE(.brf) — 텍스트·점자 블록이 같은 id를 쓰므로 이 값으로 가른다 */
    @Column(nullable = false, length = 16)
    private String panel;

    @Column(name = "element_id", nullable = false, length = 64)
    private String elementId;

    /** 블록 안 몇 번째 줄 (0-based) */
    @Column(name = "line_index", nullable = false)
    private int lineIndex;

    /** 그 줄의 원문 앞부분 — 줄이 밀렸을 때 재탐색용. 없어도 동작한다 */
    @Column(name = "line_text", length = LINE_TEXT_MAX)
    private String lineText;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public PageMemo(UUID pageId, String panel, String elementId, int lineIndex,
                    String lineText, String body, LocalDateTime createdAt) {
        this.pageId = pageId;
        this.panel = panel;
        this.elementId = elementId;
        this.lineIndex = lineIndex;
        this.lineText = trim(lineText);
        this.body = body;
        LocalDateTime now = LocalDateTime.now();
        // FE가 .smj에 들고 있던 작성 시각을 그대로 보내면 보존한다 — 없으면 지금
        this.createdAt = createdAt == null ? now : createdAt;
        this.updatedAt = now;
    }

    /** 상한을 넘는 단서는 잘라 담는다 — 앞부분만으로 충분하고, 길이 때문에 저장이 실패하면 안 된다 */
    private static String trim(String lineText) {
        if (lineText == null) return null;
        return lineText.length() <= LINE_TEXT_MAX ? lineText : lineText.substring(0, LINE_TEXT_MAX);
    }
}

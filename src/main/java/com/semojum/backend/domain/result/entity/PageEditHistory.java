package com.semojum.backend.domain.result.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 수정 이력(RLHF 학습용) — 쪽당 1행(V33). 원본 쪽 · AI 초안 · 이전 수정 · 최근 수정을 텍스트·점자 두 패널로 담는다.
// 저장할 때마다 최근 → 이전으로 밀고 새 값을 최근에 넣는다. 첫 수정의 이전 = AI 초안.
// 행 키는 pages.id — 원본 쪽 삭제가 뒤 번호를 당기므로 page_no로 찾으면 다른 쪽 행을 덮는다.
// page_edit_logs(저장 1번 = 1행)를 대체한다.
@Entity
@Table(name = "page_edit_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PageEditHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @Column(nullable = false, unique = true, columnDefinition = "uuid", updatable = false)
    private UUID pageId;

    @Column(nullable = false)
    private String jobId;

    // 마지막 저장 시점의 쪽 번호 (기록용)
    @Column(nullable = false)
    private int pageNo;

    // 마지막으로 저장한 사용자
    @Column(nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(nullable = false)
    private String mode; // "a" | "b" | "c"

    // 원본 쪽 — a/c: PDF 경로 + 이미지 크기(블록별 bbox는 스냅샷 안), b: 변환에 쓴 원문 텍스트
    private String sourcePdfPath;
    private Integer imageWidth;
    private Integer imageHeight;

    @Column(columnDefinition = "text")
    private String sourceText;

    // 패널 스냅샷 — [{id, type, heading_level, contents, origin, ai_original, bounding_box}] 읽기 순서대로.
    // 그 모드에 없는 패널은 null(b의 텍스트는 sourceText, 구 mode a엔 점자 없음)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> aiText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> aiBraille;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> prevText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> prevBraille;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> latestText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> latestBraille;

    // 최근 저장에서 직접 고친 패널: "text" | "braille"
    @Column(length = 16)
    private String editedPanel;

    // 최근 저장의 diff 요약 — {edited, added, deleted, reordered[, braille_synced | draft_selected, text_synced]}
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> changed;

    @Column(nullable = false)
    private int editCount;

    @Column(nullable = false, columnDefinition = "timestamptz", updatable = false)
    private Instant createdAt;

    @Column(nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;

    /** 첫 수정 — 원본 쪽 정보와 AI 초안을 고정하고, 이전 수정 = AI 초안 */
    @Builder
    public PageEditHistory(UUID pageId, String jobId, String mode, String sourcePdfPath, Integer imageWidth,
                           Integer imageHeight, String sourceText,
                           List<Map<String, Object>> aiText, List<Map<String, Object>> aiBraille) {
        this.pageId = pageId;
        this.jobId = jobId;
        this.mode = mode;
        this.sourcePdfPath = sourcePdfPath;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.sourceText = sourceText;
        this.aiText = aiText;
        this.aiBraille = aiBraille;
        this.latestText = aiText;
        this.latestBraille = aiBraille;
        this.createdAt = Instant.now();
    }

    /** 저장 1번 — 최근 수정을 이전으로 밀고 새 상태를 최근에 넣는다 */
    public void record(UUID userId, int pageNo, String editedPanel,
                       List<Map<String, Object>> text, List<Map<String, Object>> braille,
                       Map<String, Object> changed) {
        this.prevText = this.latestText;
        this.prevBraille = this.latestBraille;
        this.latestText = text;
        this.latestBraille = braille;
        this.userId = userId;
        this.pageNo = pageNo;
        this.editedPanel = editedPanel;
        this.changed = changed;
        this.editCount++;
        this.updatedAt = Instant.now();
    }
}

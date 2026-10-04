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

// 페이지 일괄 저장 이력(RLHF 학습용). 1저장 = 1행, 쪽의 텍스트·점자 두 패널 before/after 스냅샷(V32).
// 요소 단위 edit_logs를 대체 — 블록 추가·이동처럼 페이지 맥락이 필요한 편집은 요소 행으로 담을 수 없었다.
@Entity
@Table(name = "page_edit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PageEditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @Column(nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(nullable = false)
    private String jobId;

    @Column(nullable = false)
    private int pageNo;

    @Column(nullable = false)
    private String mode; // "a" | "b" | "c"

    // 사용자가 직접 고친 패널: "text" | "braille" (V32 — 구 element_type TEXT|BRAILLE 대체)
    @Column(length = 16)
    private String editedPanel;

    // 저장 직전/직후 쪽의 두 패널 전체 상태 (V32) — 각각 [{id, type, heading_level, contents, origin, ai_original,
    // bounding_box}] 읽기 순서대로. 3패널(mode c)은 텍스트 저장 때 점자가 재점역돼 함께 바뀌므로 두 패널을 다 담는다.
    // 패널이 없는 모드는 null: TXT(mode b)의 텍스트는 원문이라 sourceText에, 구 mode a엔 점자가 없다.
    // origin="user"(사용자 추가 블록)는 ai_original=null — before에 없고 after에만 나타난다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> beforeText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> afterText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> beforeBraille;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> afterBraille;

    // 이번 저장의 diff 요약 — {edited:[element_id..], added:[..], deleted:[..], reordered:bool}. 학습 시 재계산 불필요.
    // 편집 패널 기준이다. 3패널 텍스트 저장은 braille_synced{edited,added,deleted}로 따라 바뀐 점자도 적는다
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> changed;

    // mode a/c: 원본 페이지 입력 정보 (바이너리는 저장 안 하고 경로만)
    private String sourcePdfPath;
    private Integer imageWidth;
    private Integer imageHeight;

    // mode b: 변환에 사용한 원본 한글 텍스트
    @Column(columnDefinition = "text")
    private String sourceText;

    @Column(nullable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @Builder
    public PageEditLog(UUID userId, String jobId, int pageNo, String mode, String editedPanel,
                       List<Map<String, Object>> beforeText, List<Map<String, Object>> afterText,
                       List<Map<String, Object>> beforeBraille, List<Map<String, Object>> afterBraille,
                       Map<String, Object> changed, String sourcePdfPath, Integer imageWidth,
                       Integer imageHeight, String sourceText) {
        this.userId = userId;
        this.jobId = jobId;
        this.pageNo = pageNo;
        this.mode = mode;
        this.editedPanel = editedPanel;
        this.beforeText = beforeText;
        this.afterText = afterText;
        this.beforeBraille = beforeBraille;
        this.afterBraille = afterBraille;
        this.changed = changed;
        this.sourcePdfPath = sourcePdfPath;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.sourceText = sourceText;
        this.createdAt = Instant.now();
    }
}

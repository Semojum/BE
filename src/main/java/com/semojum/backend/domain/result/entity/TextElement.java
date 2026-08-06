package com.semojum.backend.domain.result.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "text_elements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TextElement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "page_result_id", nullable = false)
    private PageResult pageResult;

    @Column(nullable = false)
    private String elementId;

    private String type;
    private Integer readingOrder;
    private Integer headingLevel;
    private Double ocrConfidence;
    private String tnText;
    private String latexString;
    private Integer selectedIdx;
    private String renderMode;
    private String visualSubtype;
    private Double subtypeConfidence;

    // 사용자가 직접 추가한 블록은 AI 원본이 없어 null (null = 사용자 작성 블록 표시)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> originalContents;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> currentContents;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> drafts;

    @Column(nullable = false)
    private boolean isBlocked;

    // 블록 삭제(soft-delete): true면 읽기 경로(buildResult)에서 제외. 점역사 편집 이력 보존용.
    @Column(nullable = false)
    private boolean isDeleted;

    @Builder
    public TextElement(PageResult pageResult, String elementId, String type,
                       Integer readingOrder, Integer headingLevel, Double ocrConfidence,
                       String tnText, String latexString, Integer selectedIdx,
                       String renderMode, String visualSubtype, Double subtypeConfidence,
                       List<String> contents, List<Map<String, Object>> drafts, boolean isBlocked) {
        this.pageResult = pageResult;
        this.elementId = elementId;
        this.type = type;
        this.readingOrder = readingOrder;
        this.headingLevel = headingLevel;
        this.ocrConfidence = ocrConfidence;
        this.tnText = tnText;
        this.latexString = latexString;
        this.selectedIdx = selectedIdx;
        this.renderMode = renderMode;
        this.visualSubtype = visualSubtype;
        this.subtypeConfidence = subtypeConfidence;
        this.originalContents = contents;
        this.currentContents = contents;
        this.drafts = drafts;
        this.isBlocked = isBlocked;
    }

    // 점역사 수정: current만 갱신(original은 보존)
    public void updateCurrentContents(List<String> contents) {
        this.currentContents = contents;
    }

    // 블록 순서변경: reading_order 갱신
    public void updateReadingOrder(Integer readingOrder) {
        this.readingOrder = readingOrder;
    }

    // 블록 삭제(soft-delete): 행은 보존하고 플래그만 true
    public void markDeleted() {
        this.isDeleted = true;
    }

    // 사용자가 직접 추가한 블록: AI 원본 없음(original=null). current는 사용자가 입력한 값 유지.
    public void markUserAuthored() {
        this.originalContents = null;
    }

    // 대체 초안 선택: 선택 번호 갱신 (-1 = 선택 해제·AI 원본 복귀)
    public void updateSelectedIdx(Integer selectedIdx) {
        this.selectedIdx = selectedIdx;
    }
}

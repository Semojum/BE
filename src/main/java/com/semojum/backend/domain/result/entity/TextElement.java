package com.semojum.backend.domain.result.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> originalContents;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> currentContents;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String drafts;

    @Column(nullable = false)
    private boolean isBlocked;

    @Builder
    public TextElement(PageResult pageResult, String elementId, String type,
                       Integer readingOrder, Integer headingLevel, Double ocrConfidence,
                       String tnText, String latexString, Integer selectedIdx,
                       String renderMode, String visualSubtype, Double subtypeConfidence,
                       List<String> contents, String drafts, boolean isBlocked) {
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
}

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
    private int elementId;

    private String type;
    private Integer readingOrder;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> originalContents;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> currentContents;

    @Column(nullable = false)
    private boolean isBlocked;

    @Builder
    public TextElement(PageResult pageResult, int elementId, String type,
                       Integer readingOrder, List<String> contents, boolean isBlocked) {
        this.pageResult = pageResult;
        this.elementId = elementId;
        this.type = type;
        this.readingOrder = readingOrder;
        this.originalContents = contents;
        this.currentContents = contents;
        this.isBlocked = isBlocked;
    }
}

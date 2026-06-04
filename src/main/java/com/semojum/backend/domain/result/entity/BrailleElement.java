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
@Table(name = "braille_elements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BrailleElement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "page_result_id", nullable = false)
    private PageResult pageResult;

    @Column(nullable = false)
    private int elementId;

    @Column(nullable = false)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> originalContent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> currentContent;

    @Column(nullable = false)
    private boolean isBlocked;

    @Builder
    public BrailleElement(PageResult pageResult, int elementId, String type,
                          List<String> content, boolean isBlocked) {
        this.pageResult = pageResult;
        this.elementId = elementId;
        this.type = type;
        this.originalContent = content;
        this.currentContent = content;
        this.isBlocked = isBlocked;
    }
}

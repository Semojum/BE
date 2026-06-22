package com.semojum.backend.domain.result.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "quality_review_flags")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QualityReviewFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "page_result_id", nullable = false)
    private PageResult pageResult;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String elementId;

    @Column(nullable = false)
    private String message;

    @Builder
    public QualityReviewFlag(PageResult pageResult, String type, String elementId, String message) {
        this.pageResult = pageResult;
        this.type = type;
        this.elementId = elementId;
        this.message = message;
    }
}

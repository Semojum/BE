package com.semojum.backend.domain.result.entity;

import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "page_results")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PageResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "page_id", nullable = false)
    private Page page;

    @Column(nullable = false)
    private int pageNumber;

    @Column(nullable = false)
    private String mode;

    @Column(nullable = false)
    private String status;

    private Integer imageWidth;
    private Integer imageHeight;
    private Double ocrConfidenceAvg;
    private Double lineOverflowRate;

    // ProcessingMeta
    private Integer processingTimeMs;
    private Double pdfLayerConfidence;
    private String routingTierUsed;
    private Boolean scanOnly;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String rawResponse;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Builder
    public PageResult(Job job, Page page, int pageNumber, String mode, String status,
                      Integer imageWidth, Integer imageHeight,
                      Double ocrConfidenceAvg, Double lineOverflowRate,
                      Integer processingTimeMs, Double pdfLayerConfidence,
                      String routingTierUsed, Boolean scanOnly,
                      String rawResponse) {
        this.job = job;
        this.page = page;
        this.pageNumber = pageNumber;
        this.mode = mode;
        this.status = status;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.ocrConfidenceAvg = ocrConfidenceAvg;
        this.lineOverflowRate = lineOverflowRate;
        this.processingTimeMs = processingTimeMs;
        this.pdfLayerConfidence = pdfLayerConfidence;
        this.routingTierUsed = routingTierUsed;
        this.scanOnly = scanOnly;
        this.rawResponse = rawResponse;
        this.createdAt = LocalDateTime.now();
    }
}

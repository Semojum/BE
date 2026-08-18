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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

    // UsageReport(proto 08.17) — AI 측정값 + BE 계산 결과. 단가가 바뀌어도 과거 값은 불변(처리 시점 확정)
    @Column(name = "layout_type")
    private String layoutType;

    @Column(name = "gpu_time_ms")
    private Long gpuTimeMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "model_usage", columnDefinition = "jsonb")
    private List<Map<String, Object>> modelUsage;

    @Column(name = "llm_cost_usd", precision = 14, scale = 9)
    private BigDecimal llmCostUsd;

    @Column(name = "gpu_cost_usd", precision = 14, scale = 9)
    private BigDecimal gpuCostUsd;

    @Column(name = "cost_krw", precision = 16, scale = 3)
    private BigDecimal costKrw;

    // 단가표에 없는 모델 포함 = 미계상 (0원으로 삼키지 않고 표시 — proto 주석 명시)
    @Column(name = "cost_uncertain", nullable = false)
    private boolean costUncertain;

    @Column(name = "pricing_config_id")
    private Long pricingConfigId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Builder
    public PageResult(Job job, Page page, int pageNumber, String mode, String status,
                      Integer imageWidth, Integer imageHeight,
                      Double ocrConfidenceAvg, Double lineOverflowRate,
                      Integer processingTimeMs, Double pdfLayerConfidence,
                      String routingTierUsed, Boolean scanOnly,
                      String rawResponse,
                      String layoutType, Long gpuTimeMs, List<Map<String, Object>> modelUsage,
                      BigDecimal llmCostUsd, BigDecimal gpuCostUsd, BigDecimal costKrw,
                      boolean costUncertain, Long pricingConfigId) {
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
        this.layoutType = layoutType;
        this.gpuTimeMs = gpuTimeMs;
        this.modelUsage = modelUsage;
        this.llmCostUsd = llmCostUsd;
        this.gpuCostUsd = gpuCostUsd;
        this.costKrw = costKrw;
        this.costUncertain = costUncertain;
        this.pricingConfigId = pricingConfigId;
        this.createdAt = LocalDateTime.now();
    }
}

package com.semojum.backend.domain.billing.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * 단가·배율 관리 변수 (AI팀 노션 "BE 관리 변수").
 * 행 추가 = 새 판 적용(이력 보존) — 최신 행이 현재 값. 수정·삭제하지 않는다.
 * config 키: modelPrices / gpuUsdPerHour / usdKrw / cardFeeRate / creditMultiplier
 */
@Entity
@Table(name = "pricing_configs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PricingConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> config;

    private String note;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Builder
    public PricingConfig(Map<String, Object> config, String note) {
        this.config = config;
        this.note = note;
    }
}

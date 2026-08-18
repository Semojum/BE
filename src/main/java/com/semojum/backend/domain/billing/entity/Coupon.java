package com.semojum.backend.domain.billing.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 쿠폰 — 체험·무료 제공용 크레딧 (T1-7에서 운영자가 발급).
 * 차감 우선순위: 유효 기간 내 잔량 있는 쿠폰부터(발급 오래된 순), 소진되면 계약 크레딧.
 * 잔량 = credit_amount − (이 쿠폰 출처의 credit_transactions 합).
 */
@Entity
@Table(name = "coupons")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid")
    private UUID organizationId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "credit_amount", nullable = false)
    private long creditAmount;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Builder
    public Coupon(UUID organizationId, String name, long creditAmount,
                  LocalDate startsOn, LocalDate endsOn) {
        this.organizationId = organizationId;
        this.name = name;
        this.creditAmount = creditAmount;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
    }
}

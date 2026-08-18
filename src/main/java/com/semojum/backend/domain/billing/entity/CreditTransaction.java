package com.semojum.backend.domain.billing.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 크레딧 차감 로그 — 성공한 쪽(COMPLETED/NEEDS_REVIEW)마다 1행.
 * UNSPECIFIED(무료)의 0 차감도 기록한다(고객 검산용 — proto 주석 명시).
 * 실패(BLOCKED) 쪽은 차감하지 않으므로 행을 만들지 않는다.
 */
@Entity
@Table(name = "credit_transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "organization_id", columnDefinition = "uuid")
    private UUID organizationId;

    @Column(name = "job_id", nullable = false)
    private String jobId;

    @Column(name = "page_no", nullable = false)
    private int pageNo;

    @Column(name = "layout_type")
    private String layoutType;

    @Column(nullable = false)
    private int amount;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Builder
    public CreditTransaction(UUID userId, UUID organizationId, String jobId,
                             int pageNo, String layoutType, int amount) {
        this.userId = userId;
        this.organizationId = organizationId;
        this.jobId = jobId;
        this.pageNo = pageNo;
        this.layoutType = layoutType;
        this.amount = amount;
    }
}

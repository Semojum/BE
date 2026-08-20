package com.semojum.backend.domain.org.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

// V3: 계정은 기관 소속으로 발급되며, 보관 정책이 기관의 계약 만료일을 참조한다.
@Entity
@Table(name = "organizations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @Column(nullable = false)
    private String name;

    // 계정 loginId 프리픽스 (예: kblib → kblib01, kblib02…). 소문자 영숫자, 전체 유일
    @Column(nullable = false, unique = true, length = 12)
    private String code;

    // 계약 만료일 (마이페이지 보관 기간 기준)
    private LocalDate contractExpiresAt;

    // 계약 시작일 (T2 계약 카드 "시작 YYYY-MM-DD")
    @Column(name = "contract_started_at")
    private LocalDate contractStartedAt;

    // 계약 유형 (V24 개편) — 유료 BASIC(200원/크레딧)·STANDARD(150)·PREMIUM(120) / 무료 FREE(체험)·COUPON(쿠폰 제공)
    // 유형별 환산 단가는 pricing_configs.creditPricesByContract(관리 변수)가 정본.
    // 생성 시 지정 가능(2026-08-20, 미지정 시 FREE), 이후 운영자 API로 수정
    @Column(name = "contract_type",
            columnDefinition = "varchar(20) not null default 'FREE'")
    private String contractType;

    // 계약으로 받은 총 크레딧 (운영자 설정). 사용량은 credit_transactions 합산
    @Column(name = "credit_allocated", insertable = false,
            columnDefinition = "bigint not null default 0")
    private long creditAllocated;

    // 삭제 표식 (V21) — 실삭제는 보관 기간 정책 확정 후. 삭제 시 소속 계정 전부 잠김
    @Column(name = "deleted_at")
    private Instant deletedAt;

    // 증빙(계산서) 받는 사람 — T2 주문 내역 하단, 기관 관리자가 수정
    @Column(name = "receipt_email", length = 100)
    private String receiptEmail;

    @Column(nullable = false)
    private String status; // ACTIVE | EXPIRED

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public Organization(String name, String code, LocalDate contractExpiresAt, String contractType) {
        this.name = name;
        this.code = code;
        this.contractExpiresAt = contractExpiresAt;
        this.contractType = contractType == null ? "FREE" : contractType;
        this.status = "ACTIVE";
        this.createdAt = LocalDateTime.now();
    }

    public void changeReceiptEmail(String receiptEmail) {
        this.receiptEmail = receiptEmail;
    }

    // ===== T1-7 기관 정보 수정 (운영자) =====
    public void changeName(String name) {
        this.name = name;
    }

    public void changeContract(String contractType, LocalDate startedAt, LocalDate expiresAt) {
        if (contractType != null) this.contractType = contractType;
        if (startedAt != null) this.contractStartedAt = startedAt;
        if (expiresAt != null) this.contractExpiresAt = expiresAt;
    }

    public void allocateCredit(long creditAllocated) {
        this.creditAllocated = creditAllocated;
    }

    public void markDeleted() {
        this.deletedAt = Instant.now();
    }
}

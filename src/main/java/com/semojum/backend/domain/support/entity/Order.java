package com.semojum.backend.domain.support.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 주문·수납 — 계약·입금은 기관과 직접 주고받고, 확인되면 운영자가 기록한다(결제 연동 없음).
 * paidAt null = 미납. creditAmount는 참고값 — 할당 크레딧 반영은 운영자가 별도로 한다.
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    public static final String INVOICE_PENDING = "PENDING";  // 발행 대기
    public static final String INVOICE_ISSUED = "ISSUED";    // 발행 완료

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid")
    private UUID organizationId;

    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(name = "amount_krw", nullable = false)
    private long amountKrw;

    @Column(name = "credit_amount")
    private Long creditAmount;

    @Column(name = "paid_at")
    private LocalDate paidAt;

    @Column(name = "invoice_status", nullable = false, length = 20)
    private String invoiceStatus;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    // 증빙(계산서·전표) 파일 (V25) — 운영자 업로드, S3 receipts/{orderId}/ (presigned GET 전용). 재업로드 = 교체
    @Column(name = "receipt_file_key", length = 300)
    private String receiptFileKey;

    @Column(name = "receipt_file_name", length = 200)
    private String receiptFileName;

    @Column(name = "receipt_uploaded_at")
    private Instant receiptUploadedAt;

    @Builder
    public Order(UUID organizationId, LocalDate orderDate, String description,
                 long amountKrw, Long creditAmount) {
        this.organizationId = organizationId;
        this.orderDate = orderDate;
        this.description = description;
        this.amountKrw = amountKrw;
        this.creditAmount = creditAmount;
        this.invoiceStatus = INVOICE_PENDING;
    }

    public void recordPayment(LocalDate paidAt) {
        this.paidAt = paidAt;
    }

    public void changeInvoiceStatus(String invoiceStatus) {
        this.invoiceStatus = invoiceStatus;
    }

    public void attachReceipt(String fileKey, String fileName) {
        this.receiptFileKey = fileKey;
        this.receiptFileName = fileName;
        this.receiptUploadedAt = Instant.now();
    }
}

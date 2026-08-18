package com.semojum.backend.domain.support.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 문의·공지·주문 (T1 운영자 작성 · T2 기관 관리자 조회/요청) */
public class SupportDto {

    // ── 공지 ──
    public record CreateNotice(
            UUID targetOrganizationId,               // null = 전체 공지
            @NotBlank @Size(max = 200) String title,
            @NotBlank String body,
            @NotNull LocalDate startsOn,
            @NotNull LocalDate endsOn
    ) {}

    public record NoticeItem(
            UUID id,
            UUID targetOrganizationId,
            String targetOrgName,      // null = 전체
            String title,
            String body,
            LocalDate startsOn,
            LocalDate endsOn,
            String displayStatus,      // SCHEDULED(예정) | ACTIVE(노출 중) | ENDED(종료)
            Instant createdAt
    ) {}

    public record OrgNotice(
            UUID id,
            String scope,              // ALL(전체) | ORG(우리 기관)
            String title,
            String body,
            LocalDate startsOn,
            LocalDate endsOn,
            Instant createdAt
    ) {}

    // ── 문의 ──
    public record InquiryItem(
            UUID id,
            String type,
            String status,
            String orgName,            // 미가입 문의는 null
            String loginId,
            String message,
            Instant createdAt,
            Instant statusChangedAt
    ) {}

    public record UpdateInquiryStatus(@NotBlank String status) {}   // OPEN | IN_REVIEW | ANSWERED

    public record CreateRequest(
            @NotBlank String type,                    // CREDIT_ADD | ACCOUNT_ISSUE
            @Size(max = 1000) String message
    ) {}

    public record RequestItem(
            UUID id,
            String type,
            String status,
            String message,
            Instant createdAt
    ) {}

    // ── 주문·수납 ──
    public record CreateOrder(
            @NotNull UUID organizationId,
            @NotNull LocalDate orderDate,
            @NotBlank @Size(max = 200) String description,
            @NotNull @PositiveOrZero Long amountKrw,
            @PositiveOrZero Long creditAmount
    ) {}

    public record UpdateOrder(
            LocalDate paidAt,          // 입금 확인 기록 (null이면 변경 없음)
            String invoiceStatus       // PENDING | ISSUED (null이면 변경 없음)
    ) {}

    public record OrderItem(
            UUID id,
            UUID organizationId,
            String orgName,
            LocalDate orderDate,
            String description,
            long amountKrw,
            Long creditAmount,
            LocalDate paidAt,          // null = 미납
            String invoiceStatus,
            Instant createdAt
    ) {}

    public record OrgOrders(String receiptEmail, List<OrgOrderItem> items) {}

    public record OrgOrderItem(
            UUID id,
            LocalDate orderDate,
            String description,
            long amountKrw,
            Long creditAmount,
            LocalDate paidAt,
            String invoiceStatus
    ) {}

    public record UpdateReceiptEmail(@Email @Size(max = 100) String email) {}   // null = 제거
}

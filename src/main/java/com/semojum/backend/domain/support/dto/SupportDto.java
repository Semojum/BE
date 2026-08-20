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

    // 로그인 전 공개 공지 (무인증) — 전체 대상 공지만, 기관 정보 미포함
    public record PublicNotice(
            UUID id, String title, String body,
            LocalDate startsOn, LocalDate endsOn, Instant createdAt
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
            String type,               // EMAIL이면 보낸 사람 자리에 senderEmail 표시
            String status,
            String orgName,            // 미가입·메일 문의는 null
            String loginId,
            String senderEmail,        // 메일 문의(EMAIL) 전용
            String subject,            // 메일 제목 (그 외 null)
            String message,
            Instant createdAt,
            Instant statusChangedAt,
            List<InquiryAttachmentItem> attachments   // 메일 첨부·인라인 이미지 메타 (V27, 없으면 빈 배열)
    ) {}

    // 문의 첨부 메타 — 다운로드는 GET /api/admin/inquiries/{inquiryId}/attachments/{attachmentId}
    public record InquiryAttachmentItem(UUID id, String fileName, String contentType, long sizeBytes) {}

    // T1-9 목록 페이지 응답 (2026-08-20 페이지네이션)
    public record InquiryPage(
            List<InquiryItem> items,
            int page, int size,
            long totalElements, int totalPages
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
            String receiptFileName,    // 증빙 파일명 (null = 미첨부)
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
            String invoiceStatus,
            String receiptFileName     // 증빙 파일명 (null = 미첨부 → 내려받기 버튼 숨김)
    ) {}

    // 증빙 내려받기 — presigned URL(15분). FE는 즉시 열거나 저장 (URL 장기 캐시 금지)
    public record ReceiptDownload(String fileName, String url) {}

    public record UpdateReceiptEmail(@Email @Size(max = 100) String email) {}   // null = 제거
}

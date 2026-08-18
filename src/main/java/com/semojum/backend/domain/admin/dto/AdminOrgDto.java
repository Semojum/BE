package com.semojum.backend.domain.admin.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** T1-6 기관·계정 통합 표 · T1-7 기관 정보 (운영자) */
public class AdminOrgDto {

    // ── T1-6 통합 목록 ──
    public record Orgs(String month, List<OrgRow> items) {}

    public record OrgRow(
            UUID orgId,
            String name,
            String code,
            String contractType,
            List<AccountRow> accounts,
            Subtotal subtotal
    ) {}

    public record AccountRow(
            String loginId,
            String alias,
            String role,
            String status,          // ACTIVE | INACTIVE(잠김)
            Instant lastLoginAt,
            long monthCredits
    ) {}

    public record Subtotal(
            int accountCount,
            long monthCredits,             // 소속 계정 전체 합 (기획: 소계 줄)
            Instant adminLastLoginAt       // 기관 관리자(ROLE_ORG_ADMIN) 기준 — 없으면 null
    ) {}

    // ── T1-7 기관 상세·수정 ──
    public record OrgDetail(
            UUID orgId,
            String name,
            String code,                   // 변경 불가 (loginId 프리픽스)
            String contractType,
            LocalDate contractStartedAt,
            LocalDate contractExpiresAt,
            long creditAllocated,
            long creditUsed,
            long creditRemaining,
            String receiptEmail,
            List<String> accountLoginIds
    ) {}

    // 부분 수정 — null인 필드는 변경 없음. 최소 하나는 있어야 함
    public record UpdateOrg(
            @Size(max = 100) String name,
            String contractType,                    // PAID | TRIAL | INTERNAL
            LocalDate contractStartedAt,
            LocalDate contractExpiresAt,
            @PositiveOrZero Long creditAllocated    // T2 크레딧 추가 요청 처리 = 이 값 상향
    ) {}

    public record DeleteOrgResult(UUID orgId, int lockedAccounts, int canceledJobs) {}

    public record DeleteAccountResult(String loginId, int canceledJobs) {}
}

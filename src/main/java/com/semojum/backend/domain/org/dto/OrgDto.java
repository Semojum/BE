package com.semojum.backend.domain.org.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** T2 기관 관리 (ROLE_ORG_ADMIN) 응답·요청 */
public class OrgDto {

    public record Dashboard(
            String orgName,
            String orgCode,
            String contractType,            // PAID | TRIAL | INTERNAL
            LocalDate contractStartedAt,
            LocalDate contractExpiresAt,
            long creditAllocated,
            long creditUsed,                // 기관 누적 사용 (credit_transactions 합)
            long creditRemaining,           // allocated − used (음수면 0으로 절삭하지 않고 그대로 — 초과 사용 노출)
            List<MonthlyUsage> monthlyUsage // 최근 6개월 (빈 달은 0)
    ) {}

    public record MonthlyUsage(String month, long credits) {}   // month = "YYYY-MM" (KST)

    // usageSince = 사용량 집계 시작일(계약 시작일, null=전체 누적)
    public record Accounts(java.time.LocalDate usageSince, List<Account> items) {}

    public record Account(
            String loginId,
            String alias,
            String status,        // ACTIVE | INACTIVE(잠김)
            String role,
            Instant lastLoginAt,
            long usedCredits,     // 계약 시작일 이후 누적 사용 크레딧
            boolean self          // 본인(기관 관리자) 여부 — 본인은 제어 불가
    ) {}

    public record UpdateAlias(@Size(max = 50) String alias) {}   // null·빈 문자열 = 별칭 제거

    public record UpdateLock(@NotNull Boolean locked) {}

    public record LockResult(String loginId, String status, int canceledJobs) {}

    public record AccountJobs(
            String loginId,
            String alias,
            LocalDate from,
            LocalDate to,
            List<AccountJob> items,
            long totalPages,
            long totalCredits
    ) {}

    public record AccountJob(
            String jobId,
            String fileName,
            String mode,
            String status,          // PENDING | IN_PROGRESS | COMPLETED | FAILED
            int totalPages,
            Integer donePages,      // 변환 중일 때만 (Redis, 장애 시 null)
            Integer failedPages,    // 실패한 쪽 수 (terminal일 때만, 부분 실패 표기용)
            Long credits,           // 소모 크레딧 — 진행 중이면 null (끝나야 확정)
            LocalDateTime startedAt,
            LocalDateTime finishedAt
    ) {}
}

package com.semojum.backend.domain.org.service;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.enums.Role;
import com.semojum.backend.domain.auth.enums.UserStatus;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.auth.repository.UserSessionRepository;
import com.semojum.backend.domain.billing.repository.CreditTransactionRepository;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.service.JobCancelService;
import com.semojum.backend.domain.job.service.JobProgressReader;
import com.semojum.backend.domain.org.dto.OrgDto;
import com.semojum.backend.domain.org.entity.Organization;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T2 기관 관리 — ROLE_ORG_ADMIN 계정이 자기 기관의 크레딧·계약·소속 계정을 본다.
 * 열람 범위(기획 확정): 목록·상태·크레딧까지. 파일 내용·접속 정보는 보이지 않는다.
 * 계정 발급·삭제·비밀번호 재발급은 세모점(운영자) 소관 — 여기는 별칭·잠금만 제어한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgAdminService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final List<String> IN_FLIGHT = List.of("PENDING", "IN_PROGRESS");
    private static final List<String> TERMINAL = List.of("COMPLETED", "FAILED");

    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final JobRepository jobRepository;
    private final JobCancelService jobCancelService;
    private final JobProgressReader jobProgressReader;

    @Transactional(readOnly = true)
    public OrgDto.Dashboard getDashboard(String adminUserId) {
        User admin = resolveOrgAdmin(adminUserId);
        Organization org = admin.getOrganization();

        long used = creditTransactionRepository.sumContractByOrganization(org.getId());

        // 최근 6개월 (이번 달 포함, 빈 달은 0)
        YearMonth thisMonth = YearMonth.now(KST);
        YearMonth fromMonth = thisMonth.minusMonths(5);
        Instant fromInstant = fromMonth.atDay(1).atStartOfDay(KST).toInstant();
        Map<String, Long> sums = new HashMap<>();
        for (Object[] row : creditTransactionRepository.monthlySumsByOrganization(org.getId(), fromInstant)) {
            sums.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        List<OrgDto.MonthlyUsage> monthly = new ArrayList<>();
        for (YearMonth m = fromMonth; !m.isAfter(thisMonth); m = m.plusMonths(1)) {
            monthly.add(new OrgDto.MonthlyUsage(m.toString(), sums.getOrDefault(m.toString(), 0L)));
        }

        return new OrgDto.Dashboard(org.getName(), org.getCode(), org.getContractType(),
                org.getContractStartedAt(), org.getContractExpiresAt(),
                org.getCreditAllocated(), used, org.getCreditAllocated() - used, monthly);
    }

    // "사용" 열 = 계약 시작일 이후 누적 사용 크레딧 (기획 확정 2026-08-20 — 월 단위 아님).
    // 계약 시작일 미설정 기관은 전체 누적
    @Transactional(readOnly = true)
    public OrgDto.Accounts getAccounts(String adminUserId) {
        User admin = resolveOrgAdmin(adminUserId);
        Organization org = admin.getOrganization();

        java.time.LocalDate started = org.getContractStartedAt();
        Instant from = started == null ? Instant.EPOCH : started.atStartOfDay(KST).toInstant();

        Map<UUID, Long> perUser = new HashMap<>();
        for (Object[] row : creditTransactionRepository.sumPerUserByOrganizationSince(org.getId(), from)) {
            perUser.put((UUID) row[0], ((Number) row[1]).longValue());
        }

        List<OrgDto.Account> items = userRepository.findByOrganizationIdAndDeletedAtIsNullOrderByLoginIdAsc(org.getId()).stream()
                .map(u -> new OrgDto.Account(u.getLoginId(), u.getAlias(),
                        u.getStatus().name(), u.getRole().name(), u.getLastLoginAt(),
                        perUser.getOrDefault(u.getId(), 0L), u.getId().equals(admin.getId())))
                .toList();
        return new OrgDto.Accounts(started, items);
    }

    @Transactional
    public void updateAlias(String adminUserId, String loginId, String alias) {
        User admin = resolveOrgAdmin(adminUserId);
        User target = resolveSameOrgTarget(admin, loginId);
        target.changeAlias(alias == null || alias.isBlank() ? null : alias.trim());
        log.info("별칭 변경: org={}, loginId={}", admin.getOrganization().getCode(), loginId);
    }

    /**
     * 잠금 = 즉시 반영(기획 확정): 세션 전부 revoke(로그인 끊김) + 진행 중 변환 취소.
     * 크레딧은 쪽 단위 차감이라 "완료된 쪽까지만 차감"이 자동으로 성립한다.
     */
    @Transactional
    public OrgDto.LockResult updateLock(String adminUserId, String loginId, boolean locked) {
        User admin = resolveOrgAdmin(adminUserId);
        User target = resolveSameOrgTarget(admin, loginId);
        if (target.getId().equals(admin.getId())) {
            log.warn("본인 계정 잠금 시도 거부: loginId={}", loginId);
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        // revokeAllActiveByUser의 clearAutomatically가 영속성 컨텍스트를 비워 엔티티가 detach된다
        // — lazy 프록시(getOrganization())는 그 뒤에 건드리면 LazyInitializationException이므로 미리 확보
        String orgCode = admin.getOrganization().getCode();

        int canceled = 0;
        if (locked) {
            target.changeStatus(UserStatus.INACTIVE);   // revoke의 flushAutomatically가 이 변경을 먼저 flush
            userSessionRepository.revokeAllActiveByUser(target, LocalDateTime.now());
            // 진행 중이던 변환 중단 — 개별 실패(이미 종료 등)는 잠금 자체를 막지 않는다
            for (Job job : jobRepository.findByUserIdAndStatusInOrderByStartedAtDesc(target.getId(), IN_FLIGHT)) {
                try {
                    jobCancelService.cancel(target.getId().toString(), job.getId());
                    canceled++;
                } catch (Exception e) {
                    log.warn("잠금 중 변환 취소 실패(계속 진행): jobId={}, error={}", job.getId(), e.getMessage());
                }
            }
            log.info("계정 잠금: org={}, loginId={}, 취소된 작업={}건", orgCode, loginId, canceled);
        } else {
            target.changeStatus(UserStatus.ACTIVE);
            log.info("계정 잠금 해제: org={}, loginId={}", orgCode, loginId);
        }
        return new OrgDto.LockResult(loginId, target.getStatus().name(), canceled);
    }

    @Transactional(readOnly = true)
    public OrgDto.AccountJobs getAccountJobs(String adminUserId, String loginId, LocalDate from, LocalDate to) {
        User admin = resolveOrgAdmin(adminUserId);
        User target = resolveSameOrgTarget(admin, loginId);

        LocalDate toDate = to != null ? to : LocalDate.now(KST);
        LocalDate fromDate = from != null ? from : toDate.minusDays(30);

        List<Job> jobs = jobRepository.findActiveByUserIdAndStartedAtRange(
                target.getId(), fromDate.atStartOfDay(), toDate.plusDays(1).atStartOfDay());

        Map<String, Long> credits = perJobCredits(jobs);
        long totalPages = 0, totalCredits = 0;
        List<OrgDto.AccountJob> items = new ArrayList<>();
        for (Job job : jobs) {
            boolean terminal = TERMINAL.contains(job.getStatus());
            Long credit = terminal ? credits.getOrDefault(job.getId(), 0L) : null;
            Integer donePages = terminal ? null : jobProgressReader.donePages(job.getId());
            Integer failedPages = terminal && job.getFailedPages() != null && job.getFailedPages().length > 0
                    ? job.getFailedPages().length : null;
            totalPages += job.getTotalPages();
            if (credit != null) totalCredits += credit;
            items.add(new OrgDto.AccountJob(job.getId(), job.getOriginalFileName(), job.getMode(),
                    job.getStatus(), job.getTotalPages(), donePages, failedPages, credit,
                    job.getStartedAt(), job.getFinishedAt()));
        }
        return new OrgDto.AccountJobs(target.getLoginId(), target.getAlias(),
                fromDate, toDate, items, totalPages, totalCredits);
    }

    // 작업별 크레딧 합 일괄 조회 (N+1 방지)
    private Map<String, Long> perJobCredits(List<Job> jobs) {
        if (jobs.isEmpty()) return Map.of();
        List<String> ids = jobs.stream().map(Job::getId).toList();
        Map<String, Long> credits = new HashMap<>();
        for (Object[] row : creditTransactionRepository.sumPerJob(ids)) {
            credits.put((String) row[0], ((Number) row[1]).longValue());
        }
        return credits;
    }

    // ROLE_ORG_ADMIN + 소속 기관 존재 확인 — 아니면 403
    private User resolveOrgAdmin(String adminUserId) {
        User admin = userRepository.findById(UUID.fromString(adminUserId))
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        if (admin.getRole() != Role.ROLE_ORG_ADMIN || admin.getOrganization() == null) {
            log.warn("기관 관리 접근 거부: loginId={}, role={}", admin.getLoginId(), admin.getRole());
            throw new CustomException(ErrorCode.COMMON_FORBIDDEN);
        }
        return admin;
    }

    // 대상 계정이 같은 기관 소속인지 확인 — 타 기관은 존재 여부를 숨기지 않고 403. 삭제 계정은 404
    private User resolveSameOrgTarget(User admin, String loginId) {
        User target = userRepository.findByLoginId(loginId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        if (target.getOrganization() == null
                || !target.getOrganization().getId().equals(admin.getOrganization().getId())) {
            log.warn("타 기관 계정 접근 거부: admin={}, target={}", admin.getLoginId(), loginId);
            throw new CustomException(ErrorCode.COMMON_FORBIDDEN);
        }
        return target;
    }
}

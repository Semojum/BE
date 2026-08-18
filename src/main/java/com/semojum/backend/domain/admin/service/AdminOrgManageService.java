package com.semojum.backend.domain.admin.service;

import com.semojum.backend.domain.admin.dto.AdminOrgDto;
import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.enums.Role;
import com.semojum.backend.domain.auth.enums.UserStatus;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.auth.repository.UserSessionRepository;
import com.semojum.backend.domain.billing.entity.Coupon;
import com.semojum.backend.domain.billing.repository.CouponRepository;
import com.semojum.backend.domain.billing.repository.CreditTransactionRepository;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.service.JobCancelService;
import com.semojum.backend.domain.org.entity.Organization;
import com.semojum.backend.domain.org.repository.OrganizationRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T1-6 기관·계정 통합 표 · T1-7 기관 정보 — 운영자의 기관 관리.
 * 삭제는 소프트(deleted_at + 잠금) — 실삭제는 보관 기간 정책 확정 후 별도.
 * "잠금"의 의미는 T2와 동일: 세션 전부 revoke + 진행 중 변환 취소(완료된 쪽까지만 차감).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOrgManageService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 유료 BASIC·STANDARD·PREMIUM / 무료 FREE(체험)·COUPON(쿠폰 제공) — V24 개편
    private static final Set<String> CONTRACT_TYPES = Set.of("BASIC", "STANDARD", "PREMIUM", "FREE", "COUPON");
    private static final List<String> IN_FLIGHT = List.of("PENDING", "IN_PROGRESS");

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final CouponRepository couponRepository;
    private final JobRepository jobRepository;
    private final JobCancelService jobCancelService;

    /** T1-6 통합 표 — 기관마다 소속 계정 + 소계(월 사용량 합·기관 관리자 마지막 로그인) */
    @Transactional(readOnly = true)
    public AdminOrgDto.Orgs listOrgs(String month) {
        YearMonth ym = month == null || month.isBlank() ? YearMonth.now(KST) : YearMonth.parse(month);
        Instant from = ym.atDay(1).atStartOfDay(KST).toInstant();
        Instant to = ym.plusMonths(1).atDay(1).atStartOfDay(KST).toInstant();

        // 계정별 월 사용량 일괄 조회 (N+1 방지)
        Map<UUID, Long> perUser = new HashMap<>();
        for (Object[] row : creditTransactionRepository.sumPerOrgUserBetween(from, to)) {
            perUser.put((UUID) row[1], ((Number) row[2]).longValue());
        }

        // 기관별 계정 그룹
        Map<UUID, List<User>> byOrg = new HashMap<>();
        for (User u : userRepository.findByOrganizationIdIsNotNullAndDeletedAtIsNullOrderByLoginIdAsc()) {
            byOrg.computeIfAbsent(u.getOrganization().getId(), k -> new ArrayList<>()).add(u);
        }

        List<AdminOrgDto.OrgRow> items = organizationRepository.findAll().stream()
                .filter(org -> org.getDeletedAt() == null)
                .sorted(java.util.Comparator.comparing(Organization::getName))
                .map(org -> {
                    List<User> users = byOrg.getOrDefault(org.getId(), List.of());
                    long subtotalCredits = 0;
                    Instant adminLastLogin = null;
                    List<AdminOrgDto.AccountRow> accounts = new ArrayList<>();
                    for (User u : users) {
                        long credits = perUser.getOrDefault(u.getId(), 0L);
                        subtotalCredits += credits;
                        if (u.getRole() == Role.ROLE_ORG_ADMIN && u.getLastLoginAt() != null
                                && (adminLastLogin == null || u.getLastLoginAt().isAfter(adminLastLogin))) {
                            adminLastLogin = u.getLastLoginAt();
                        }
                        accounts.add(new AdminOrgDto.AccountRow(u.getLoginId(), u.getAlias(),
                                u.getRole().name(), u.getStatus().name(), u.getLastLoginAt(), credits));
                    }
                    return new AdminOrgDto.OrgRow(org.getId(), org.getName(), org.getCode(),
                            org.getContractType(), accounts,
                            new AdminOrgDto.Subtotal(users.size(), subtotalCredits, adminLastLogin));
                }).toList();
        return new AdminOrgDto.Orgs(ym.toString(), items);
    }

    /** T1-7 기관 상세 — 계약·크레딧(할당/사용/잔여). 주문·수납은 GET /api/admin/orders?organizationId= */
    @Transactional(readOnly = true)
    public AdminOrgDto.OrgDetail getOrg(UUID orgId) {
        Organization org = findActiveOrg(orgId);
        long used = creditTransactionRepository.sumContractByOrganization(orgId);
        List<String> loginIds = userRepository.findByOrganizationIdAndDeletedAtIsNullOrderByLoginIdAsc(orgId)
                .stream().map(User::getLoginId).toList();
        return new AdminOrgDto.OrgDetail(org.getId(), org.getName(), org.getCode(),
                org.getContractType(), org.getContractStartedAt(), org.getContractExpiresAt(),
                org.getCreditAllocated(), used, org.getCreditAllocated() - used,
                org.getReceiptEmail(), loginIds);
    }

    /** T1-7 기관 정보 수정 — 이름·계약·할당 크레딧(T2 크레딧 추가 요청 처리 = 할당 상향) */
    @Transactional
    public AdminOrgDto.OrgDetail updateOrg(UUID orgId, AdminOrgDto.UpdateOrg request) {
        if (request.name() == null && request.contractType() == null && request.contractStartedAt() == null
                && request.contractExpiresAt() == null && request.creditAllocated() == null) {
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        Organization org = findActiveOrg(orgId);

        if (request.name() != null) {
            if (request.name().isBlank()) throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
            org.changeName(request.name().trim());
        }
        if (request.contractType() != null && !CONTRACT_TYPES.contains(request.contractType())) {
            log.warn("계약 구분 값 오류: {}", request.contractType());
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        org.changeContract(request.contractType(), request.contractStartedAt(), request.contractExpiresAt());
        // 수정 결과 기준으로 기간 역전 검증 (한쪽만 바꾼 경우 포함)
        if (org.getContractStartedAt() != null && org.getContractExpiresAt() != null
                && org.getContractExpiresAt().isBefore(org.getContractStartedAt())) {
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        if (request.creditAllocated() != null) {
            org.allocateCredit(request.creditAllocated());
        }
        log.info("기관 정보 수정: org={}, 할당={}, 계약={}~{} ({})", org.getCode(),
                org.getCreditAllocated(), org.getContractStartedAt(), org.getContractExpiresAt(), org.getContractType());
        return getOrgAfterUpdate(org);
    }

    private AdminOrgDto.OrgDetail getOrgAfterUpdate(Organization org) {
        long used = creditTransactionRepository.sumContractByOrganization(org.getId());
        List<String> loginIds = userRepository.findByOrganizationIdAndDeletedAtIsNullOrderByLoginIdAsc(org.getId())
                .stream().map(User::getLoginId).toList();
        return new AdminOrgDto.OrgDetail(org.getId(), org.getName(), org.getCode(),
                org.getContractType(), org.getContractStartedAt(), org.getContractExpiresAt(),
                org.getCreditAllocated(), used, org.getCreditAllocated() - used,
                org.getReceiptEmail(), loginIds);
    }

    /** 기관 삭제(소프트) — 소속 계정 전부 잠금(세션 revoke + 진행 중 변환 취소). 자료는 보관 */
    @Transactional
    public AdminOrgDto.DeleteOrgResult deleteOrg(UUID orgId) {
        Organization org = findActiveOrg(orgId);
        List<User> users = userRepository.findByOrganizationIdAndDeletedAtIsNullOrderByLoginIdAsc(orgId);
        int canceled = 0;
        for (User user : users) {
            user.changeStatus(UserStatus.INACTIVE);
            userSessionRepository.revokeAllActiveByUser(user, LocalDateTime.now());
            canceled += cancelInFlightJobs(user);
        }
        org.markDeleted();
        log.info("기관 삭제(소프트): org={}, 잠긴 계정={}건, 취소된 변환={}건", org.getCode(), users.size(), canceled);
        return new AdminOrgDto.DeleteOrgResult(orgId, users.size(), canceled);
    }

    /** 계정 삭제(소프트) — 잠금 + 삭제 표식. 작업물은 보관 */
    @Transactional
    public AdminOrgDto.DeleteAccountResult deleteAccount(String loginId) {
        User user = userRepository.findByLoginId(loginId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        user.markDeleted();   // INACTIVE 전환 포함
        userSessionRepository.revokeAllActiveByUser(user, LocalDateTime.now());
        int canceled = cancelInFlightJobs(user);
        log.info("계정 삭제(소프트): loginId={}, 취소된 변환={}건", loginId, canceled);
        return new AdminOrgDto.DeleteAccountResult(loginId, canceled);
    }

    // 진행 중 변환 취소 — 개별 실패는 삭제·잠금 자체를 막지 않는다 (T2 잠금과 동일 규칙)
    private int cancelInFlightJobs(User user) {
        int canceled = 0;
        for (Job job : jobRepository.findByUserIdAndStatusInOrderByStartedAtDesc(user.getId(), IN_FLIGHT)) {
            try {
                jobCancelService.cancel(user.getId().toString(), job.getId());
                canceled++;
            } catch (Exception e) {
                log.warn("삭제·잠금 중 변환 취소 실패(계속 진행): jobId={}, error={}", job.getId(), e.getMessage());
            }
        }
        return canceled;
    }

    /** T1-7 쿠폰 발급 — 체험·무료 제공. 차감은 쿠폰부터(CreditDeductionService) */
    @Transactional
    public AdminOrgDto.CouponItem issueCoupon(UUID orgId, AdminOrgDto.CreateCoupon request) {
        if (request.endsOn().isBefore(request.startsOn())) {
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        Organization org = findActiveOrg(orgId);
        Coupon saved = couponRepository.save(Coupon.builder()
                .organizationId(orgId)
                .name(request.name().trim())
                .creditAmount(request.creditAmount())
                .startsOn(request.startsOn())
                .endsOn(request.endsOn())
                .build());
        log.info("쿠폰 발급: org={}, name={}, {}크레딧, {}~{}", org.getCode(),
                request.name(), request.creditAmount(), request.startsOn(), request.endsOn());
        return toCouponItem(couponRepository.findById(saved.getId()).orElse(saved));
    }

    @Transactional(readOnly = true)
    public List<AdminOrgDto.CouponItem> listCoupons(UUID orgId) {
        findActiveOrg(orgId);
        return couponRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId).stream()
                .map(this::toCouponItem).toList();
    }

    private AdminOrgDto.CouponItem toCouponItem(Coupon coupon) {
        long used = creditTransactionRepository.sumByCoupon(coupon.getId());
        long remaining = coupon.getCreditAmount() - used;
        java.time.LocalDate today = java.time.LocalDate.now(KST);
        String status = today.isBefore(coupon.getStartsOn()) ? "SCHEDULED"
                : today.isAfter(coupon.getEndsOn()) ? "ENDED"
                : remaining <= 0 ? "EXHAUSTED" : "ACTIVE";
        return new AdminOrgDto.CouponItem(coupon.getId(), coupon.getName(), coupon.getCreditAmount(),
                used, remaining, coupon.getStartsOn(), coupon.getEndsOn(), status);
    }

    private Organization findActiveOrg(UUID orgId) {
        return organizationRepository.findById(orgId)
                .filter(o -> o.getDeletedAt() == null)
                .orElseThrow(() -> new CustomException(ErrorCode.ORG_NOT_FOUND));
    }
}

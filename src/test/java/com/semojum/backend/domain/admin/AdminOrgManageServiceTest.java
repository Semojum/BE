package com.semojum.backend.domain.admin;

import com.semojum.backend.domain.admin.dto.AdminOrgDto;
import com.semojum.backend.domain.admin.service.AdminOrgManageService;
import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.enums.UserStatus;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.auth.repository.UserSessionRepository;
import com.semojum.backend.domain.billing.repository.CouponRepository;
import com.semojum.backend.domain.billing.repository.CreditTransactionRepository;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.service.JobCancelService;
import com.semojum.backend.domain.org.entity.Organization;
import com.semojum.backend.domain.org.repository.OrganizationRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// T1-6·T1-7 기관 관리 규칙 (저장소 mock)
class AdminOrgManageServiceTest {

    private OrganizationRepository organizationRepository;
    private UserRepository userRepository;
    private UserSessionRepository userSessionRepository;
    private JobRepository jobRepository;
    private AdminOrgManageService service;

    private Organization org;
    private User member;

    private static void setId(Object entity, Object value) throws Exception {
        var f = entity.getClass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(entity, value);
    }

    @BeforeEach
    void setUp() throws Exception {
        organizationRepository = Mockito.mock(OrganizationRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        userSessionRepository = Mockito.mock(UserSessionRepository.class);
        jobRepository = Mockito.mock(JobRepository.class);
        service = new AdminOrgManageService(organizationRepository, userRepository, userSessionRepository,
                Mockito.mock(CreditTransactionRepository.class), Mockito.mock(CouponRepository.class),
                jobRepository, Mockito.mock(JobCancelService.class));

        org = Organization.builder().name("기관A").code("orga")
                .contractExpiresAt(LocalDate.of(2027, 12, 31)).build();
        setId(org, UUID.randomUUID());
        when(organizationRepository.findById(org.getId())).thenReturn(Optional.of(org));

        member = User.builder().loginId("orga01").organization(org).password("pw").build();
        setId(member, UUID.randomUUID());
        when(userRepository.findByLoginId("orga01")).thenReturn(Optional.of(member));
        when(userRepository.findByOrganizationIdAndDeletedAtIsNullOrderByLoginIdAsc(org.getId()))
                .thenReturn(List.of(member));
        when(jobRepository.findByUserIdAndStatusInOrderByStartedAtDesc(any(), anyList()))
                .thenReturn(List.of());
    }

    @Test
    void 수정은_변경_항목이_하나는_있어야_하고_계약_구분_검증() {
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, assertThrows(CustomException.class, () ->
                service.updateOrg(org.getId(), new AdminOrgDto.UpdateOrg(null, null, null, null, null)))
                .getErrorCode());
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, assertThrows(CustomException.class, () ->
                service.updateOrg(org.getId(), new AdminOrgDto.UpdateOrg(null, "FREE", null, null, null)))
                .getErrorCode());
    }

    @Test
    void 할당_크레딧_설정과_계약_수정() {
        var detail = service.updateOrg(org.getId(), new AdminOrgDto.UpdateOrg(
                null, "TRIAL", LocalDate.of(2026, 8, 1), null, 10_000L));
        assertEquals(10_000L, detail.creditAllocated());
        assertEquals("TRIAL", detail.contractType());
        assertEquals(LocalDate.of(2026, 8, 1), detail.contractStartedAt());
        assertEquals(LocalDate.of(2027, 12, 31), detail.contractExpiresAt());   // 미변경 유지
    }

    @Test
    void 수정_결과_기준_계약_기간_역전_거절() {
        // 시작일만 만료일 뒤로 바꾸는 경우도 잡아야 함
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, assertThrows(CustomException.class, () ->
                service.updateOrg(org.getId(), new AdminOrgDto.UpdateOrg(
                        null, null, LocalDate.of(2028, 1, 1), null, null)))
                .getErrorCode());
    }

    @Test
    void 기관_삭제는_소속_계정_전부_잠금() {
        var result = service.deleteOrg(org.getId());
        assertEquals(1, result.lockedAccounts());
        assertEquals(UserStatus.INACTIVE, member.getStatus());
        assertNotNull(org.getDeletedAt());
        verify(userSessionRepository).revokeAllActiveByUser(eq(member), any());
        // 삭제된 기관은 이후 조회·수정 불가
        assertEquals(ErrorCode.ORG_NOT_FOUND, assertThrows(CustomException.class,
                () -> service.getOrg(org.getId())).getErrorCode());
    }

    @Test
    void 계정_삭제는_잠금과_삭제_표식() {
        var result = service.deleteAccount("orga01");
        assertEquals("orga01", result.loginId());
        assertNotNull(member.getDeletedAt());
        assertEquals(UserStatus.INACTIVE, member.getStatus());
        verify(userSessionRepository).revokeAllActiveByUser(eq(member), any());
        // 이미 삭제된 계정 재삭제 → 404
        assertEquals(ErrorCode.USER_NOT_FOUND, assertThrows(CustomException.class,
                () -> service.deleteAccount("orga01")).getErrorCode());
    }
}

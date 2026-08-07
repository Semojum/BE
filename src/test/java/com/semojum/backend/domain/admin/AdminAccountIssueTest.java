package com.semojum.backend.domain.admin;

import com.semojum.backend.domain.admin.dto.AdminRequestDto;
import com.semojum.backend.domain.admin.dto.AdminResponseDto;
import com.semojum.backend.domain.admin.service.AdminService;
import com.semojum.backend.domain.auth.enums.Role;
import com.semojum.backend.domain.auth.enums.UserStatus;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.auth.repository.UserSessionRepository;
import com.semojum.backend.domain.org.repository.OrganizationRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// 실제 PostgreSQL(Testcontainers)로 기관 코드·계정 일괄 발급 규칙을 검증한다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Testcontainers
class AdminAccountIssueTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired UserSessionRepository userSessionRepository;

    AdminService adminService;

    // BCrypt 실연산은 불필요 — 발급 규칙 검증이 목적
    static final PasswordEncoder NOOP_ENCODER = new PasswordEncoder() {
        @Override public String encode(CharSequence rawPassword) { return "enc:" + rawPassword; }
        @Override public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return encodedPassword.equals("enc:" + rawPassword);
        }
    };

    @BeforeEach
    void setUp() {
        adminService = new AdminService(organizationRepository, userRepository, userSessionRepository, NOOP_ENCODER);
    }

    private String createOrg(String name, String code) {
        return adminService.createOrganization(
                new AdminRequestDto.CreateOrg(name, code, null)).organizationId();
    }

    @Test
    void 코드를_주면_그_코드로_생성되고_중복이면_거부된다() {
        AdminResponseDto.Org org = adminService.createOrganization(
                new AdminRequestDto.CreateOrg("한국점자도서관", "kblib", null));
        assertEquals("kblib", org.code());

        CustomException e = assertThrows(CustomException.class,
                () -> createOrg("다른기관", "kblib"));
        assertEquals(ErrorCode.ORG_CODE_DUPLICATE, e.getErrorCode());
    }

    @Test
    void 코드_미입력_시_orgNN이_순서대로_자동_부여된다() {
        assertEquals("org01", adminService.createOrganization(
                new AdminRequestDto.CreateOrg("기관A", null, null)).code());
        assertEquals("org02", adminService.createOrganization(
                new AdminRequestDto.CreateOrg("기관B", null, null)).code());
    }

    @Test
    void 수량만큼_기관코드_순번으로_발급되고_추가_발급은_이어서_번호가_붙는다() {
        String orgId = createOrg("한국점자도서관", "kblib");

        List<AdminResponseDto.IssuedAccount> first = adminService.issueAccounts(
                new AdminRequestDto.IssueAccounts(orgId, 3)).accounts();
        assertEquals(List.of("kblib01", "kblib02", "kblib03"),
                first.stream().map(AdminResponseDto.IssuedAccount::loginId).toList());
        assertTrue(first.stream().allMatch(a -> a.password().length() == 12));

        List<AdminResponseDto.IssuedAccount> second = adminService.issueAccounts(
                new AdminRequestDto.IssueAccounts(orgId, 2)).accounts();
        assertEquals(List.of("kblib04", "kblib05"),
                second.stream().map(AdminResponseDto.IssuedAccount::loginId).toList());
    }

    @Test
    void 프리픽스가_겹치는_다른_기관의_계정은_순번_계산에_섞이지_않는다() {
        String kb = createOrg("KB기관", "kb");
        String kblib = createOrg("한국점자도서관", "kblib");

        adminService.issueAccounts(new AdminRequestDto.IssueAccounts(kblib, 2)); // kblib01, kblib02
        List<AdminResponseDto.IssuedAccount> kbAccounts = adminService.issueAccounts(
                new AdminRequestDto.IssueAccounts(kb, 1)).accounts();

        // kblib01은 "kb"로 시작하지만 숫자 접미사가 아니므로 kb의 순번에 영향 없음
        assertEquals("kb01", kbAccounts.get(0).loginId());
    }

    @Test
    void 발급된_계정은_ACTIVE_상태로_생성된다() {
        String orgId = createOrg("기관C", "orgc");
        adminService.issueAccounts(new AdminRequestDto.IssueAccounts(orgId, 1));
        assertEquals(UserStatus.ACTIVE,
                userRepository.findByLoginId("orgc01").orElseThrow().getStatus());
    }

    @Test
    void 상태를_INACTIVE로_바꾸면_저장되고_다시_ACTIVE로_되돌릴_수_있다() {
        String orgId = createOrg("기관D", "orgd");
        adminService.issueAccounts(new AdminRequestDto.IssueAccounts(orgId, 1));

        AdminResponseDto.AccountStatus off = adminService.updateStatus("orgd01",
                new AdminRequestDto.UpdateStatus(UserStatus.INACTIVE));
        assertEquals("INACTIVE", off.status());
        assertFalse(userRepository.findByLoginId("orgd01").orElseThrow().isActive());

        AdminResponseDto.AccountStatus on = adminService.updateStatus("orgd01",
                new AdminRequestDto.UpdateStatus(UserStatus.ACTIVE));
        assertEquals("ACTIVE", on.status());
        assertTrue(userRepository.findByLoginId("orgd01").orElseThrow().isActive());
    }

    @Test
    void 발급된_계정은_ROLE_USER이고_운영자가_ROLE_ADMIN으로_바꿀_수_있다() {
        String orgId = createOrg("기관E", "orge");
        adminService.issueAccounts(new AdminRequestDto.IssueAccounts(orgId, 1));
        assertEquals(Role.ROLE_USER,
                userRepository.findByLoginId("orge01").orElseThrow().getRole());

        AdminResponseDto.AccountRole changed = adminService.updateRole("orge01",
                new AdminRequestDto.UpdateRole(Role.ROLE_ADMIN));
        assertEquals("ROLE_ADMIN", changed.role());
        assertEquals(Role.ROLE_ADMIN,
                userRepository.findByLoginId("orge01").orElseThrow().getRole());
    }

    @Test
    void 없는_계정의_상태_변경은_404다() {
        CustomException e = assertThrows(CustomException.class,
                () -> adminService.updateStatus("ghost01",
                        new AdminRequestDto.UpdateStatus(UserStatus.INACTIVE)));
        assertEquals(ErrorCode.USER_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void 없는_기관이면_404다() {
        CustomException e = assertThrows(CustomException.class,
                () -> adminService.issueAccounts(new AdminRequestDto.IssueAccounts(
                        "00000000-0000-0000-0000-000000000000", 1)));
        assertEquals(ErrorCode.ORG_NOT_FOUND, e.getErrorCode());
    }
}

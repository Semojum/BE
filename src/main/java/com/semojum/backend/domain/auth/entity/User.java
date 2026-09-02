package com.semojum.backend.domain.auth.entity;

import com.semojum.backend.domain.auth.enums.Role;
import com.semojum.backend.domain.auth.enums.UserStatus;
import com.semojum.backend.domain.org.entity.Organization;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    // V3 발급형 계정 로그인 ID (운영자가 발급, 1인 1계정)
    @Column(name = "login_id", unique = true, nullable = false)
    private String loginId;

    // 소속 기관 (V3 발급 계정은 필수, 레거시 행은 null)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @Column
    private String password;

    // 계정 상태 — INACTIVE면 로그인·토큰 재발급 차단 (운영자만 변경)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    // 계정 역할 — ROLE_ADMIN은 운영·테스트용 계정 분류 (운영자만 변경)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    // ROLE_ADMIN 사용처 구분 (V28) — WEB: 운영자 콘솔 전용(Origin이 콘솔 주소일 때만 로그인, 앱 불가)
    //                              APP: 에디터 앱용(웹 관리자의 "마이페이지로 보내기" 수신 대상)
    // null = 스코프 미지정. 콘솔 로그인만 막히고 나머지는 종전과 동일하게 동작하며, 현재 보유 계정은 없다.
    //        ROLE_ADMIN 외 계정에선 의미 없음
    @Column(name = "admin_scope", length = 10)
    private String adminScope;

    public static final String ADMIN_SCOPE_WEB = "WEB";
    public static final String ADMIN_SCOPE_APP = "APP";

    // 별칭 — 기관 관리자가 "누가 쓰는 계정인지"를 역할명으로 적는다 (실명 대신 "수학 담당" 권장)
    @Column(length = 50)
    private String alias;

    // 마지막 로그인 시각 (T1-6·T2 소속 계정 표) — 로그인 성공 시 갱신
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    // 삭제 표식 (V21) — 실삭제는 보관 기간 정책 확정 후. 삭제 시 INACTIVE로도 전환돼 로그인 차단
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public User(String loginId, Organization organization, String password) {
        this.loginId = loginId;
        this.organization = organization;
        this.password = password;
        this.status = UserStatus.ACTIVE;
        this.role = Role.ROLE_USER;
        this.createdAt = LocalDateTime.now();
    }

    // 운영자 비밀번호 재발급 (사용자 스스로 변경은 불가 정책)
    public void reissuePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    public void changeStatus(UserStatus status) {
        this.status = status;
    }

    public void changeRole(Role role) {
        this.role = role;
    }

    public void changeAlias(String alias) {
        this.alias = alias;
    }

    public void touchLastLogin() {
        this.lastLoginAt = Instant.now();
    }

    public void markDeleted() {
        this.deletedAt = Instant.now();
        this.status = UserStatus.INACTIVE;   // 기존 차단 경로(로그인·refresh AUTH4004) 재사용
    }

    public boolean isActive() {
        return this.status == UserStatus.ACTIVE;
    }
}
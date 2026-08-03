package com.semojum.backend.domain.auth.entity;

import com.semojum.backend.domain.org.entity.Organization;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public User(String loginId, Organization organization, String password) {
        this.loginId = loginId;
        this.organization = organization;
        this.password = password;
        this.createdAt = LocalDateTime.now();
    }

    // 운영자 비밀번호 재발급 (사용자 스스로 변경은 불가 정책)
    public void reissuePassword(String encodedPassword) {
        this.password = encodedPassword;
    }
}
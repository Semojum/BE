package com.semojum.backend.domain.auth.repository;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    // 유효한 세션 조회 (revoked_at이 null인 것)
    Optional<UserSession> findByUserAndRefreshTokenHashAndRevokedAtIsNull(User user, String refreshTokenHash);
}
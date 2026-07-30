package com.semojum.backend.domain.auth.repository;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    // 유효한 세션 조회 (revoked_at이 null인 것)
    Optional<UserSession> findByUserAndRefreshTokenHashAndRevokedAtIsNull(User user, String refreshTokenHash);

    // V3 중복 로그인 금지: 새 로그인 시 기존 활성 세션을 전부 revoke (신규가 기존을 밀어냄)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserSession s SET s.revokedAt = :now WHERE s.user = :user AND s.revokedAt IS NULL")
    int revokeAllActiveByUser(@Param("user") User user, @Param("now") LocalDateTime now);
}
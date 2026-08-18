package com.semojum.backend.domain.auth.repository;

import com.semojum.backend.domain.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    // V3: 발급형 계정 로그인
    Optional<User> findByLoginId(String loginId);

    // 기관 소속 계정 목록 (T2 소속 계정 표 — 발급 순서 = loginId 순)
    java.util.List<User> findByOrganizationIdOrderByLoginIdAsc(java.util.UUID organizationId);
    boolean existsByLoginId(String loginId);

    // 계정 일괄 발급 시 다음 순번 계산용 — 기관 코드 프리픽스로 시작하는 loginId 전체
    @Query("select u.loginId from User u where u.loginId like concat(:prefix, '%')")
    List<String> findLoginIdsByPrefix(@Param("prefix") String prefix);
}

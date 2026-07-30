package com.semojum.backend.domain.auth.repository;

import com.semojum.backend.domain.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    // V3: 발급형 계정 로그인
    Optional<User> findByLoginId(String loginId);
    boolean existsByLoginId(String loginId);
}

package com.semojum.backend.domain.auth.repository;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.enums.AuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    Optional<User> findByProviderAndProviderUid(AuthProvider provider, String providerUid);
}
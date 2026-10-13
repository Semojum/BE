package com.semojum.backend.domain.user.repository;

import com.semojum.backend.domain.user.entity.BrailleForm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BrailleFormRepository extends JpaRepository<BrailleForm, UUID> {

    List<BrailleForm> findByUserIdOrderByCreatedAtAsc(UUID userId);

    /** 남의 양식에 손대지 못하도록 소유자까지 함께 건다 — 없으면 404가 아니라 조회 자체가 비는 편이 안전하다 */
    Optional<BrailleForm> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndName(UUID userId, String name);

    boolean existsByUserIdAndNameAndIdNot(UUID userId, String name, UUID id);
}

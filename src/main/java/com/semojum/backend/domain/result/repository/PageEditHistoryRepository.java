package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.PageEditHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PageEditHistoryRepository extends JpaRepository<PageEditHistory, UUID> {
    Optional<PageEditHistory> findByPageId(UUID pageId);
}

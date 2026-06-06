package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.PageResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PageResultRepository extends JpaRepository<PageResult, UUID> {
    Optional<PageResult> findByJobIdAndPageNumber(String jobId, int pageNumber);
}

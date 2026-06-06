package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.PageResult;
import com.semojum.backend.domain.result.entity.QualityReviewFlag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QualityReviewFlagRepository extends JpaRepository<QualityReviewFlag, UUID> {
    List<QualityReviewFlag> findByPageResult(PageResult pageResult);
}

package com.semojum.backend.domain.result.repository;

import java.util.Collection;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import com.semojum.backend.domain.result.entity.PageResult;
import com.semojum.backend.domain.result.entity.QualityReviewFlag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QualityReviewFlagRepository extends JpaRepository<QualityReviewFlag, UUID> {
    List<QualityReviewFlag> findByPageResult(PageResult pageResult);

    @Modifying
    @Query("DELETE FROM QualityReviewFlag x WHERE x.pageResult.id IN :pageResultIds")
    int deleteByPageResultIdIn(@Param("pageResultIds") Collection<UUID> pageResultIds);
}

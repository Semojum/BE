package com.semojum.backend.domain.result.repository;

import java.util.Collection;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import com.semojum.backend.domain.result.entity.PageResult;
import com.semojum.backend.domain.result.entity.QualityCriticalError;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QualityCriticalErrorRepository extends JpaRepository<QualityCriticalError, UUID> {
    List<QualityCriticalError> findByPageResult(PageResult pageResult);

    // T1-4 쪽별 결과의 "사유" 열 — 페이지 결과 여러 건의 오류를 한 번에
    List<QualityCriticalError> findByPageResultIdIn(List<UUID> pageResultIds);

    @Modifying
    @Query("DELETE FROM QualityCriticalError x WHERE x.pageResult.id IN :pageResultIds")
    int deleteByPageResultIdIn(@Param("pageResultIds") Collection<UUID> pageResultIds);
}

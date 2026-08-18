package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.PageResult;
import com.semojum.backend.domain.result.entity.QualityCriticalError;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QualityCriticalErrorRepository extends JpaRepository<QualityCriticalError, UUID> {
    List<QualityCriticalError> findByPageResult(PageResult pageResult);

    // T1-4 쪽별 결과의 "사유" 열 — 페이지 결과 여러 건의 오류를 한 번에
    List<QualityCriticalError> findByPageResultIdIn(List<UUID> pageResultIds);
}

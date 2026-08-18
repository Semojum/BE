package com.semojum.backend.domain.billing.repository;

import com.semojum.backend.domain.billing.entity.CreditTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, UUID> {

    List<CreditTransaction> findByJobIdOrderByPageNo(String jobId);

    // 워커 재시도 재진입 시 이중 차감 방지 (DB 유니크 인덱스가 최종 방어선)
    boolean existsByJobIdAndPageNo(String jobId, int pageNo);

    // 작업 총 소모 크레딧 (기록 없으면 0)
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM CreditTransaction t WHERE t.jobId = :jobId")
    long sumAmountByJobId(@Param("jobId") String jobId);
}

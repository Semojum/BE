package com.semojum.backend.domain.billing.repository;

import com.semojum.backend.domain.billing.entity.CreditTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, UUID> {

    List<CreditTransaction> findByJobIdOrderByPageNo(String jobId);

    // 워커 재시도 재진입 시 이중 차감 방지 (DB 유니크 인덱스가 최종 방어선)
    boolean existsByJobIdAndPageNo(String jobId, int pageNo);

    // 작업 총 소모 크레딧 (기록 없으면 0)
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM CreditTransaction t WHERE t.jobId = :jobId")
    long sumAmountByJobId(@Param("jobId") String jobId);

    // 기관 누적 사용량 (잔여 = organizations.credit_allocated − 이 값)
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM CreditTransaction t WHERE t.organizationId = :orgId")
    long sumByOrganization(@Param("orgId") UUID orgId);

    // 계정의 기간 사용량 (T3 이번 달/지난달)
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM CreditTransaction t " +
            "WHERE t.userId = :userId AND t.createdAt >= :from AND t.createdAt < :to")
    long sumByUserBetween(@Param("userId") UUID userId,
                          @Param("from") Instant from, @Param("to") Instant to);

    // 기관별 기간 차감 합 (T1-2 수익성 — 환산 매출의 축) — [organizationId, sum]
    @Query("SELECT t.organizationId, COALESCE(SUM(t.amount), 0) FROM CreditTransaction t " +
            "WHERE t.organizationId IS NOT NULL AND t.createdAt >= :from AND t.createdAt < :to " +
            "GROUP BY t.organizationId")
    List<Object[]> sumPerOrganizationBetween(@Param("from") Instant from, @Param("to") Instant to);

    // 전 기관 계정별 기간 사용량 (T1-6 통합 표) — [organizationId, userId, sum]
    @Query("SELECT t.organizationId, t.userId, COALESCE(SUM(t.amount), 0) FROM CreditTransaction t " +
            "WHERE t.createdAt >= :from AND t.createdAt < :to GROUP BY t.organizationId, t.userId")
    List<Object[]> sumPerOrgUserBetween(@Param("from") Instant from, @Param("to") Instant to);

    // 기관 소속 계정별 기간 사용량 (T2 소속 계정 표의 "사용" 열) — [userId(UUID), sum(Long)]
    @Query("SELECT t.userId, COALESCE(SUM(t.amount), 0) FROM CreditTransaction t " +
            "WHERE t.organizationId = :orgId AND t.createdAt >= :from AND t.createdAt < :to GROUP BY t.userId")
    List<Object[]> sumPerUserByOrganizationBetween(@Param("orgId") UUID orgId,
                                                   @Param("from") Instant from, @Param("to") Instant to);

    // 기관 월별 사용 추이 (T2 차트) — KST 월 기준 [월("YYYY-MM"), sum]
    @Query(value = "SELECT to_char(created_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM') AS month, " +
            "COALESCE(SUM(amount), 0) FROM credit_transactions " +
            "WHERE organization_id = :orgId AND created_at >= :from GROUP BY month ORDER BY month",
            nativeQuery = true)
    List<Object[]> monthlySumsByOrganization(@Param("orgId") UUID orgId, @Param("from") Instant from);

    // 작업별 소모 크레딧 일괄 조회 (목록 화면 N+1 방지) — [jobId, sum]
    @Query("SELECT t.jobId, COALESCE(SUM(t.amount), 0) FROM CreditTransaction t " +
            "WHERE t.jobId IN :jobIds GROUP BY t.jobId")
    List<Object[]> sumPerJob(@Param("jobIds") List<String> jobIds);
}

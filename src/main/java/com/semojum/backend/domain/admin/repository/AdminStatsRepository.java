package com.semojum.backend.domain.admin.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * T1 통계 전용 네이티브 집계 (date_trunc 버킷).
 * jobs.started_at·page_results.created_at은 KST 고정 LocalDateTime — 타임존 변환 불필요.
 * 쪽 수는 COUNT(*) — 워커 재시도로 드문 중복 행이 있을 수 있으나 통계 용도로 허용(과금은 credit_transactions가 정본).
 * 관리자 사본(jobs.admin_copy)은 전 쿼리에서 제외 — 실제 변환이 아니므로 건수·쪽수·원가에 안 센다 (V28).
 */
@Repository
@RequiredArgsConstructor
public class AdminStatsRepository {

    private static final Set<String> UNITS = Set.of("hour", "day", "week", "month");

    @PersistenceContext
    private EntityManager em;

    // date_trunc 단위는 바인딩 불가(식별자) — 화이트리스트 검증 후 문자열 삽입
    private static String unit(String unit) {
        if (!UNITS.contains(unit)) throw new IllegalArgumentException("unsupported unit: " + unit);
        return unit;
    }

    /** 기간 내 시작된 작업의 상태 분포: [status, canceled(bool), count] */
    @SuppressWarnings("unchecked")
    public List<Object[]> jobStatusCounts(LocalDateTime from, LocalDateTime to) {
        return em.createNativeQuery(
                        "SELECT status, canceled_at IS NOT NULL, COUNT(*) FROM jobs " +
                        "WHERE started_at >= :f AND started_at < :t AND admin_copy = false GROUP BY 1, 2")
                .setParameter("f", from).setParameter("t", to).getResultList();
    }

    /** 기간 내 처리(성공) 쪽수 */
    public long successPages(LocalDateTime from, LocalDateTime to) {
        Object result = em.createNativeQuery(
                        "SELECT COUNT(*) FROM page_results p JOIN jobs j ON p.job_id = j.id " +
                        "WHERE p.status IN ('COMPLETED','NEEDS_REVIEW') AND p.created_at >= :f AND p.created_at < :t " +
                        "AND j.admin_copy = false")
                .setParameter("f", from).setParameter("t", to).getSingleResult();
        return ((Number) result).longValue();
    }

    /** 처리 쪽수 시계열: [bucket(timestamp), pages] */
    @SuppressWarnings("unchecked")
    public List<Object[]> pagesSeries(LocalDateTime from, LocalDateTime to, String bucketUnit) {
        return em.createNativeQuery(
                        "SELECT date_trunc('" + unit(bucketUnit) + "', p.created_at) AS b, COUNT(*) " +
                        "FROM page_results p JOIN jobs j ON p.job_id = j.id " +
                        "WHERE p.status IN ('COMPLETED','NEEDS_REVIEW') AND p.created_at >= :f AND p.created_at < :t " +
                        "AND j.admin_copy = false GROUP BY b ORDER BY b")
                .setParameter("f", from).setParameter("t", to).getResultList();
    }

    /** 시작 작업 건수 시계열: [bucket(timestamp), jobs] — 요청 시각(started_at) 기준 */
    @SuppressWarnings("unchecked")
    public List<Object[]> jobsSeries(LocalDateTime from, LocalDateTime to, String bucketUnit) {
        return em.createNativeQuery(
                        "SELECT date_trunc('" + unit(bucketUnit) + "', started_at) AS b, COUNT(*) FROM jobs " +
                        "WHERE started_at >= :f AND started_at < :t AND admin_copy = false " +
                        "GROUP BY b ORDER BY b")
                .setParameter("f", from).setParameter("t", to).getResultList();
    }

    /** 기간 원가 합: [Σcost_krw, 미계상 포함] */
    @SuppressWarnings("unchecked")
    public Object[] costSum(LocalDateTime from, LocalDateTime to) {
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT COALESCE(SUM(p.cost_krw), 0), COALESCE(BOOL_OR(p.cost_uncertain), false) " +
                        "FROM page_results p JOIN jobs j ON p.job_id = j.id " +
                        "WHERE p.created_at >= :f AND p.created_at < :t AND j.admin_copy = false")
                .setParameter("f", from).setParameter("t", to).getResultList();
        return rows.get(0);
    }

    /** 작업량 스택: [bucket, 완료(취소 제외), 실패·취소] — 진행 중 작업은 어느 쪽에도 안 셈 */
    @SuppressWarnings("unchecked")
    public List<Object[]> workload(LocalDateTime from, String bucketUnit) {
        return em.createNativeQuery(
                        "SELECT date_trunc('" + unit(bucketUnit) + "', started_at) AS b, " +
                        "COUNT(*) FILTER (WHERE status = 'COMPLETED' AND canceled_at IS NULL), " +
                        "COUNT(*) FILTER (WHERE status = 'FAILED' OR canceled_at IS NOT NULL) " +
                        "FROM jobs WHERE started_at >= :f AND admin_copy = false GROUP BY b ORDER BY b")
                .setParameter("f", from).getResultList();
    }

    /** 기관별 기간 원가 (T1-2 수익성): [organization_id, Σcost_krw, 미계상 포함] — 쪽→작업→계정→기관 */
    @SuppressWarnings("unchecked")
    public List<Object[]> orgCostSums(LocalDateTime from, LocalDateTime to) {
        return em.createNativeQuery(
                        "SELECT u.organization_id, COALESCE(SUM(p.cost_krw), 0), " +
                        "COALESCE(BOOL_OR(p.cost_uncertain), false) " +
                        "FROM page_results p JOIN jobs j ON p.job_id = j.id JOIN users u ON j.user_id = u.id " +
                        "WHERE u.organization_id IS NOT NULL AND p.created_at >= :f AND p.created_at < :t " +
                        "AND j.admin_copy = false GROUP BY u.organization_id")
                .setParameter("f", from).setParameter("t", to).getResultList();
    }

    /** 레이아웃 유형별 집계: [layout_type, pages, Σcost_krw] */
    @SuppressWarnings("unchecked")
    public List<Object[]> layoutCost(LocalDateTime from, LocalDateTime to) {
        return em.createNativeQuery(
                        "SELECT p.layout_type, COUNT(*), COALESCE(SUM(p.cost_krw), 0) " +
                        "FROM page_results p JOIN jobs j ON p.job_id = j.id " +
                        "WHERE p.layout_type IS NOT NULL AND p.status IN ('COMPLETED','NEEDS_REVIEW') " +
                        "AND p.created_at >= :f AND p.created_at < :t AND j.admin_copy = false " +
                        "GROUP BY p.layout_type")
                .setParameter("f", from).setParameter("t", to).getResultList();
    }
}

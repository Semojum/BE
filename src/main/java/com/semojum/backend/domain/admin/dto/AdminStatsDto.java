package com.semojum.backend.domain.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** T1-1 대표 통계 · T1-2 상세 통계 (운영자). 기관별 수익성은 계약 단가 확정 후 추가 */
public class AdminStatsDto {

    // ── T1-1 개요 ──
    public record Overview(
            String period,               // today | week | month
            LocalDateTime from,
            LocalDateTime to,
            JobCounts jobs,              // 기간 내 시작된 작업 (건수 KPI)
            long pagesProcessed,         // 기간 내 처리(성공) 쪽수
            long prevPagesProcessed,     // 직전 기간 전체 (예: "어제 1,020쪽" 비교)
            List<SeriesPoint> series,    // 차트 — today: 시간별 / week·month: 일별
            CostSummary cost             // 누적 원가 패널 (period와 무관하게 고정 구성)
    ) {}

    public record JobCounts(long total, long completed, long inProgress, long failed) {}

    public record SeriesPoint(LocalDateTime bucket, long pages) {}

    public record CostSummary(
            BigDecimal todayKrw,
            BigDecimal yesterdayKrw,
            BigDecimal thisWeekDailyAvgKrw,   // 이번 주 누적 ÷ 경과 일수
            BigDecimal lastWeekDailyAvgKrw,
            BigDecimal thisWeekTotalKrw,
            BigDecimal thisMonthTotalKrw,
            long thisMonthPages,
            BigDecimal krwPerPage,            // 이번 달 쪽당 원가
            boolean uncertain                 // 이번 달 미계상 모델 포함 여부
    ) {}

    // ── T1-2 작업량 (완료 / 실패·취소 스택) ──
    public record Workload(String unit, List<WorkloadPoint> buckets) {}

    public record WorkloadPoint(LocalDateTime bucket, long completed, long failedOrCanceled) {}

    // ── T1-2 레이아웃 유형별 평균 원가 ──
    public record LayoutCost(String month, List<LayoutCostItem> items) {}

    public record LayoutCostItem(
            String layoutType,
            long pages,
            double sharePct,              // 전체 쪽수 대비 비중 (%)
            BigDecimal avgKrwPerPage,
            Double pagesDeltaPct          // 전월 대비 쪽수 증감률 (%) — 전월 0쪽이면 null
    ) {}

    // ── T1-2 기관별 수익성 (차액 = 환산 매출 − 원가) ──
    public record Profitability(
            String month,
            java.util.Map<String, Long> creditPricesByContract,   // 계약 유형별 단가 (관리 변수 — 조회 시점 최신 판)
            List<ProfitabilityItem> items,
            ProfitabilityTotals totals
    ) {}

    public record ProfitabilityItem(
            java.util.UUID orgId,
            String orgName,
            String contractType,          // BASIC | STANDARD | PREMIUM | FREE | COUPON
            long appliedPriceKrw,         // 이 기관에 적용된 크레딧 단가 (유형별)
            long creditsUsed,             // 월 차감 크레딧 (계약분 — 쿠폰 차감 제외)
            BigDecimal revenueKrw,        // 환산 매출 = creditsUsed × appliedPriceKrw
            BigDecimal costKrw,
            BigDecimal marginKrw,         // 차액 — 음수면 밑지는 기관 (화면 빨간 막대)
            boolean costUncertain         // 미계상 모델 포함 (원가 과소 표시 가능)
    ) {}

    public record ProfitabilityTotals(
            long creditsUsed, BigDecimal revenueKrw, BigDecimal costKrw, BigDecimal marginKrw
    ) {}
}

package com.semojum.backend.domain.admin.service;

import com.semojum.backend.domain.admin.dto.AdminStatsDto;
import com.semojum.backend.domain.admin.repository.AdminStatsRepository;
import com.semojum.backend.domain.billing.repository.CreditTransactionRepository;
import com.semojum.backend.domain.billing.repository.PricingConfigRepository;
import com.semojum.backend.domain.org.entity.Organization;
import com.semojum.backend.domain.org.repository.OrganizationRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * T1-1 대표 통계 · T1-2 상세 통계. 서버 시각 = KST 고정이므로 LocalDate/Time 그대로 버킷팅.
 * 기관별 수익성(T1-2 3번 패널)은 계약 단가(크레딧당 원화) 확정 후 추가한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminStatsService {

    private final AdminStatsRepository statsRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final OrganizationRepository organizationRepository;
    private final PricingConfigRepository pricingConfigRepository;

    /** T1-1 — 기간 탭(today/week/month)에 따른 건수·쪽수·시계열 + 누적 원가 패널 */
    @Transactional(readOnly = true)
    public AdminStatsDto.Overview getOverview(String period) {
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        LocalDateTime from, prevFrom, prevTo;
        String seriesUnit;
        switch (period == null ? "today" : period) {
            case "today" -> {
                from = today.atStartOfDay();
                prevFrom = today.minusDays(1).atStartOfDay();
                prevTo = from;
                seriesUnit = "hour";
            }
            case "week" -> {
                from = today.with(DayOfWeek.MONDAY).atStartOfDay();
                prevFrom = from.minusWeeks(1);
                prevTo = from;
                seriesUnit = "day";
            }
            case "month" -> {
                from = today.withDayOfMonth(1).atStartOfDay();
                prevFrom = from.minusMonths(1);
                prevTo = from;
                seriesUnit = "day";
            }
            default -> {
                log.warn("통계 기간 파라미터 오류: {}", period);
                throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
            }
        }

        // 건수 — 기간 내 시작된 작업의 상태 분포 (취소는 실패로 분류하지 않고 완료/실패 규칙 그대로: 취소=canceled_at 표식)
        long total = 0, completed = 0, inProgress = 0, failed = 0;
        for (Object[] row : statsRepository.jobStatusCounts(from, now)) {
            String status = (String) row[0];
            boolean canceled = (Boolean) row[1];
            long count = ((Number) row[2]).longValue();
            total += count;
            if ("FAILED".equals(status) || canceled) failed += count;
            else if ("COMPLETED".equals(status)) completed += count;
            else inProgress += count;
        }

        long pages = statsRepository.successPages(from, now);
        long prevPages = statsRepository.successPages(prevFrom, prevTo);

        List<AdminStatsDto.SeriesPoint> series = statsRepository.pagesSeries(from, now, seriesUnit).stream()
                .map(row -> new AdminStatsDto.SeriesPoint(toLocalDateTime(row[0]), ((Number) row[1]).longValue()))
                .toList();

        return new AdminStatsDto.Overview(period == null ? "today" : period, from, now,
                new AdminStatsDto.JobCounts(total, completed, inProgress, failed),
                pages, prevPages, series, buildCostSummary(today, now));
    }

    // 누적 원가 패널 — 오늘·어제·주 평균을 한 축에(기획), 우측에 주·월 누적
    private AdminStatsDto.CostSummary buildCostSummary(LocalDate today, LocalDateTime now) {
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime weekStart = today.with(DayOfWeek.MONDAY).atStartOfDay();
        LocalDateTime lastWeekStart = weekStart.minusWeeks(1);
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();

        BigDecimal todayKrw = krw(statsRepository.costSum(todayStart, now));
        BigDecimal yesterdayKrw = krw(statsRepository.costSum(todayStart.minusDays(1), todayStart));
        BigDecimal thisWeekTotal = krw(statsRepository.costSum(weekStart, now));
        BigDecimal lastWeekTotal = krw(statsRepository.costSum(lastWeekStart, weekStart));
        Object[] monthRow = statsRepository.costSum(monthStart, now);
        BigDecimal monthTotal = krw(monthRow);
        long monthPages = statsRepository.successPages(monthStart, now);

        int daysElapsed = today.getDayOfWeek().getValue();   // 월=1 … 일=7
        BigDecimal thisWeekAvg = thisWeekTotal.divide(BigDecimal.valueOf(daysElapsed), 3, RoundingMode.HALF_UP);
        BigDecimal lastWeekAvg = lastWeekTotal.divide(BigDecimal.valueOf(7), 3, RoundingMode.HALF_UP);
        BigDecimal krwPerPage = monthPages == 0 ? BigDecimal.ZERO
                : monthTotal.divide(BigDecimal.valueOf(monthPages), 3, RoundingMode.HALF_UP);

        return new AdminStatsDto.CostSummary(todayKrw, yesterdayKrw, thisWeekAvg, lastWeekAvg,
                thisWeekTotal, monthTotal, monthPages, krwPerPage, (Boolean) monthRow[1]);
    }

    private static BigDecimal krw(Object[] costRow) {
        return (BigDecimal) costRow[0];
    }

    // 시각 컬럼은 timestamptz(V11) — 드라이버·하이버네이트 버전에 따라 반환 타입이 달라 전부 수용 (표시는 KST)
    private static LocalDateTime toLocalDateTime(Object o) {
        if (o instanceof Timestamp t) return t.toLocalDateTime();
        if (o instanceof OffsetDateTime odt) return odt.atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDateTime();
        if (o instanceof Instant i) return LocalDateTime.ofInstant(i, ZoneId.of("Asia/Seoul"));
        if (o instanceof LocalDateTime l) return l;
        throw new IllegalStateException("지원하지 않는 시각 타입: " + o.getClass().getName());
    }

    /** T1-2 작업량 — 완료 / 실패·취소 스택. unit: daily(14일)·weekly(8주)·monthly(6개월)·all(월별 전체) */
    @Transactional(readOnly = true)
    public AdminStatsDto.Workload getWorkload(String unit) {
        LocalDate today = LocalDate.now();
        String bucketUnit;
        LocalDateTime from;
        switch (unit == null ? "weekly" : unit) {
            case "daily" -> { bucketUnit = "day"; from = today.minusDays(13).atStartOfDay(); }
            case "weekly" -> { bucketUnit = "week"; from = today.with(DayOfWeek.MONDAY).minusWeeks(7).atStartOfDay(); }
            case "monthly" -> { bucketUnit = "month"; from = today.withDayOfMonth(1).minusMonths(5).atStartOfDay(); }
            case "all" -> { bucketUnit = "month"; from = LocalDateTime.of(2020, 1, 1, 0, 0); }
            default -> {
                log.warn("작업량 단위 파라미터 오류: {}", unit);
                throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
            }
        }
        List<AdminStatsDto.WorkloadPoint> buckets = statsRepository.workload(from, bucketUnit).stream()
                .map(row -> new AdminStatsDto.WorkloadPoint(toLocalDateTime(row[0]),
                        ((Number) row[1]).longValue(), ((Number) row[2]).longValue()))
                .toList();
        return new AdminStatsDto.Workload(unit == null ? "weekly" : unit, buckets);
    }

    /** T1-2 레이아웃 유형별 평균 원가 — 비싼 순 정렬(요금 설계 근거), 전월 대비 쪽수 증감 */
    @Transactional(readOnly = true)
    public AdminStatsDto.LayoutCost getLayoutCost(String month) {
        YearMonth ym = month == null || month.isBlank() ? YearMonth.now() : YearMonth.parse(month);
        LocalDateTime from = ym.atDay(1).atStartOfDay();
        LocalDateTime to = ym.plusMonths(1).atDay(1).atStartOfDay();

        Map<String, Long> prevPages = new HashMap<>();
        for (Object[] row : statsRepository.layoutCost(from.minusMonths(1), from)) {
            prevPages.put((String) row[0], ((Number) row[1]).longValue());
        }

        List<Object[]> rows = statsRepository.layoutCost(from, to);
        long totalPages = rows.stream().mapToLong(r -> ((Number) r[1]).longValue()).sum();

        List<AdminStatsDto.LayoutCostItem> items = new ArrayList<>();
        for (Object[] row : rows) {
            String layout = (String) row[0];
            long pages = ((Number) row[1]).longValue();
            BigDecimal cost = (BigDecimal) row[2];
            BigDecimal avg = pages == 0 ? BigDecimal.ZERO
                    : cost.divide(BigDecimal.valueOf(pages), 3, RoundingMode.HALF_UP);
            double share = totalPages == 0 ? 0 : Math.round(pages * 1000.0 / totalPages) / 10.0;
            Long prev = prevPages.get(layout);
            Double delta = prev == null || prev == 0 ? null
                    : Math.round((pages - prev) * 1000.0 / prev) / 10.0;
            items.add(new AdminStatsDto.LayoutCostItem(layout, pages, share, avg, delta));
        }
        items.sort((a, b) -> b.avgKrwPerPage().compareTo(a.avgKrwPerPage()));   // 비싼 순
        return new AdminStatsDto.LayoutCost(ym.toString(), items);
    }

    /**
     * T1-2 기관별 수익성 — 차액 = 환산 매출(차감 크레딧 × 판매 단가) − 원가.
     * 매출은 조회 시점 최신 단가로 환산하는 파생값(단가 변경 시 과거 월도 재환산) —
     * 확정 회계는 orders의 실제 계약 금액이 담당. 원가·차감 원본은 처리 시점 확정 저장분.
     */
    @Transactional(readOnly = true)
    public AdminStatsDto.Profitability getProfitability(String month) {
        YearMonth ym = month == null || month.isBlank() ? YearMonth.now() : YearMonth.parse(month);
        LocalDateTime from = ym.atDay(1).atStartOfDay();
        LocalDateTime to = ym.plusMonths(1).atDay(1).atStartOfDay();
        java.time.Instant fromI = from.atZone(ZoneId.of("Asia/Seoul")).toInstant();
        java.time.Instant toI = to.atZone(ZoneId.of("Asia/Seoul")).toInstant();

        long creditPrice = pricingConfigRepository.findTopByOrderByIdDesc()
                .map(pc -> pc.getConfig().get("creditPriceKrw"))
                .map(v -> new BigDecimal(String.valueOf(v)).longValue())
                .orElse(0L);
        if (creditPrice == 0L) log.warn("creditPriceKrw 미설정 — 환산 매출이 0으로 계산됨");

        Map<java.util.UUID, Long> credits = new HashMap<>();
        for (Object[] row : creditTransactionRepository.sumPerOrganizationBetween(fromI, toI)) {
            credits.put((java.util.UUID) row[0], ((Number) row[1]).longValue());
        }
        Map<java.util.UUID, Object[]> costs = new HashMap<>();
        for (Object[] row : statsRepository.orgCostSums(from, to)) {
            costs.put((java.util.UUID) row[0], row);
        }
        Map<java.util.UUID, Organization> orgs = new HashMap<>();
        for (Organization org : organizationRepository.findAll()) orgs.put(org.getId(), org);

        // 크레딧·원가 어느 쪽이든 데이터가 있는 기관 전부 (삭제 기관도 과거 월 이력은 표시)
        java.util.Set<java.util.UUID> orgIds = new java.util.LinkedHashSet<>();
        orgIds.addAll(credits.keySet());
        orgIds.addAll(costs.keySet());

        long totalCredits = 0;
        BigDecimal totalRevenue = BigDecimal.ZERO, totalCost = BigDecimal.ZERO;
        java.util.List<AdminStatsDto.ProfitabilityItem> items = new java.util.ArrayList<>();
        for (java.util.UUID orgId : orgIds) {
            long used = credits.getOrDefault(orgId, 0L);
            Object[] costRow = costs.get(orgId);
            BigDecimal cost = costRow == null ? BigDecimal.ZERO : (BigDecimal) costRow[1];
            boolean uncertain = costRow != null && (Boolean) costRow[2];
            BigDecimal revenue = BigDecimal.valueOf(used * creditPrice);
            Organization org = orgs.get(orgId);
            items.add(new AdminStatsDto.ProfitabilityItem(orgId,
                    org != null ? org.getName() : null,
                    org != null ? org.getContractType() : null,
                    used, revenue, cost, revenue.subtract(cost), uncertain));
            totalCredits += used;
            totalRevenue = totalRevenue.add(revenue);
            totalCost = totalCost.add(cost);
        }
        items.sort((a, b) -> b.marginKrw().compareTo(a.marginKrw()));   // 차액 큰 순 (밑지는 기관이 아래)
        return new AdminStatsDto.Profitability(ym.toString(), creditPrice, items,
                new AdminStatsDto.ProfitabilityTotals(totalCredits, totalRevenue, totalCost,
                        totalRevenue.subtract(totalCost)));
    }
}

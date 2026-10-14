package com.semojum.backend.domain.user.service;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.billing.repository.CreditTransactionRepository;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.service.JobProgressReader;
import com.semojum.backend.domain.user.dto.UsageDto;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T3 사용량 — 점역사 본인의 크레딧 소모를 본다.
 * 열람 범위(기획 확정): 내 사용량 + 기관 할당·잔여까지. 다른 계정의 개별 소모량은 보이지 않는다.
 */
@Service
@RequiredArgsConstructor
public class UsageService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final List<String> TERMINAL = List.of("COMPLETED", "FAILED");

    private final UserRepository userRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final JobRepository jobRepository;
    private final JobProgressReader jobProgressReader;

    @Transactional(readOnly = true)
    public UsageDto.Summary getSummary(String userId, String month) {
        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        YearMonth ym = month == null || month.isBlank() ? YearMonth.now(KST) : YearMonth.parse(month);
        Instant from = ym.atDay(1).atStartOfDay(KST).toInstant();
        Instant to = ym.plusMonths(1).atDay(1).atStartOfDay(KST).toInstant();

        long myCredits = creditTransactionRepository.sumByUserBetween(user.getId(), from, to);

        // 기관 잔여는 전체 기준(월 무관) — "우리 기관 남은 크레딧" 카드
        Long orgAllocated = null, orgUsed = null, orgRemaining = null;
        if (user.getOrganization() != null) {
            orgAllocated = user.getOrganization().getCreditAllocated();
            orgUsed = creditTransactionRepository.sumContractByOrganization(user.getOrganization().getId());
            orgRemaining = orgAllocated - orgUsed;
        }
        return new UsageDto.Summary(ym.toString(), myCredits, orgAllocated, orgUsed, orgRemaining);
    }

    /** 기간 선택 — 피그마 [이 달 · 지난 3달 · 올해] */
    static final List<String> PERIODS = List.of("MONTH", "QUARTER", "YEAR");
    static final List<String> SOURCES = List.of("PDF_HWP", "IMAGE", "TEXT");
    private static final java.util.Set<String> IMAGE_EXTS = java.util.Set.of("jpg", "jpeg", "png");

    /**
     * 마이페이지 [사용량] — 일별 막대 · 입력 종류별 내역 · 상단 카드.
     *
     * <p>기간은 오늘 기준이다(2026-10-14 결정). 끝은 늘 오늘이라 앞으로 올 날은 막대에 없다.
     * 날짜 경계는 KST. 운영자 「마이페이지로 보내기」 사본(admin_copy)의 차감은 빼고 센다.
     */
    @Transactional(readOnly = true)
    public UsageDto.Daily getDaily(String userId, String period) {
        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        String p = period == null || period.isBlank() ? "MONTH" : period;
        if (!PERIODS.contains(p)) throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);

        LocalDate today = LocalDate.now(KST);
        LocalDate from = periodStart(p, today);
        List<Object[]> rows = creditTransactionRepository.findUsageRowsByUserBetween(user.getId(),
                from.atStartOfDay(KST).toInstant(), today.plusDays(1).atStartOfDay(KST).toInstant());

        Map<String, Job> jobs = new HashMap<>();
        java.util.Set<String> jobIds = new java.util.HashSet<>();
        for (Object[] r : rows) jobIds.add((String) r[2]);
        if (!jobIds.isEmpty()) jobRepository.findAllById(jobIds).forEach(j -> jobs.put(j.getId(), j));

        Map<LocalDate, Long> perDay = new java.util.TreeMap<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) perDay.put(d, 0L);
        Map<String, Long> perSource = new java.util.LinkedHashMap<>();
        SOURCES.forEach(src -> perSource.put(src, 0L));
        long used = 0;
        for (Object[] r : rows) {
            Job job = jobs.get((String) r[2]);
            if (job != null && job.isAdminCopy()) continue;
            long amount = ((Number) r[1]).longValue();
            LocalDate day = LocalDate.ofInstant((Instant) r[0], KST);
            perDay.merge(day, amount, Long::sum);
            perSource.merge(sourceOf(job), amount, Long::sum);
            used += amount;
        }

        Long orgAllocated = null, orgRemaining = null;
        Double usedPercent = null;
        if (user.getOrganization() != null) {
            orgAllocated = user.getOrganization().getCreditAllocated();
            orgRemaining = orgAllocated - creditTransactionRepository.sumContractByOrganization(user.getOrganization().getId());
            if (orgAllocated > 0) usedPercent = Math.round(used * 1000.0 / orgAllocated) / 10.0;
        }
        return new UsageDto.Daily(p, from, today, orgAllocated, orgRemaining, used, usedPercent,
                perDay.entrySet().stream().map(e -> new UsageDto.DailyCredit(e.getKey(), e.getValue())).toList(),
                perSource.entrySet().stream().map(e -> new UsageDto.SourceCredit(e.getKey(), e.getValue())).toList());
    }

    /** 오늘 기준 기간 시작일 — 이 달 1일 · 3개월 전 같은 날 · 1월 1일 */
    public static LocalDate periodStart(String period, LocalDate today) {
        return switch (period) {
            case "QUARTER" -> today.minusMonths(3);
            case "YEAR" -> today.withDayOfYear(1);
            default -> today.withDayOfMonth(1);
        };
    }

    /**
     * 입력 종류 — 텍스트 작업(mode b)은 TEXT, 이미지로 만든 작업은 IMAGE(이름이 첫 장 파일명), 나머지는 PDF_HWP.
     * 작업을 못 찾으면(지워진 작업 등) PDF_HWP로 센다 — 텍스트 작업은 차감이 없어 거의 해당되지 않는다.
     */
    static String sourceOf(Job job) {
        if (job == null) return "PDF_HWP";
        if ("b".equals(job.getMode())) return "TEXT";
        String name = job.getOriginalFileName() == null ? "" : job.getOriginalFileName();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        return IMAGE_EXTS.contains(ext) ? "IMAGE" : "PDF_HWP";
    }

    @Transactional(readOnly = true)
    public UsageDto.Jobs getJobs(String userId, LocalDate from, LocalDate to) {
        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        LocalDate toDate = to != null ? to : LocalDate.now(KST);
        LocalDate fromDate = from != null ? from : toDate.minusDays(30);

        List<Job> jobs = jobRepository.findActiveByUserIdAndStartedAtRange(
                user.getId(), fromDate.atStartOfDay(), toDate.plusDays(1).atStartOfDay());

        Map<String, Long> credits = new HashMap<>();
        if (!jobs.isEmpty()) {
            List<String> ids = jobs.stream().map(Job::getId).toList();
            for (Object[] row : creditTransactionRepository.sumPerJob(ids)) {
                credits.put((String) row[0], ((Number) row[1]).longValue());
            }
        }

        long totalCredits = 0;
        List<UsageDto.UsageJob> items = new ArrayList<>();
        for (Job job : jobs) {
            boolean terminal = TERMINAL.contains(job.getStatus());
            Long credit = terminal ? credits.getOrDefault(job.getId(), 0L) : null;
            Integer donePages = terminal ? null : jobProgressReader.donePages(job.getId());
            Integer failedPages = terminal && job.getFailedPages() != null && job.getFailedPages().length > 0
                    ? job.getFailedPages().length : null;
            if (credit != null) totalCredits += credit;
            items.add(new UsageDto.UsageJob(job.getId(), job.getOriginalFileName(), job.getMode(),
                    job.getStatus(), job.getTotalPages(), donePages, failedPages, credit, job.getFinishedAt()));
        }
        return new UsageDto.Jobs(fromDate, toDate, items, totalCredits);
    }
}

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

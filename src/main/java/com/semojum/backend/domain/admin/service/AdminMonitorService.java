package com.semojum.backend.domain.admin.service;

import com.semojum.backend.domain.admin.dto.AdminMonitorDto;
import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.billing.entity.CreditTransaction;
import com.semojum.backend.domain.billing.repository.CreditTransactionRepository;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.job.service.JobProgressReader;
import com.semojum.backend.domain.result.entity.PageResult;
import com.semojum.backend.domain.result.entity.QualityCriticalError;
import com.semojum.backend.domain.result.repository.PageResultRepository;
import com.semojum.backend.domain.result.repository.QualityCriticalErrorRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T1-3 실시간 모니터링 · T1-4 작업 상세 — 운영자 전용(전 기관).
 * 접속 정보·원가까지 포함하므로 운영자 열람 범위(계약서 명시 대상)에만 노출한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMonitorService {

    private static final Set<String> JOB_STATUSES = Set.of("PENDING", "IN_PROGRESS", "COMPLETED", "FAILED");
    private static final Set<String> IN_FLIGHT = Set.of("PENDING", "IN_PROGRESS");
    private static final Set<String> PAGE_SUCCESS = Set.of("COMPLETED", "NEEDS_REVIEW");

    private final JobRepository jobRepository;
    private final PageRepository pageRepository;
    private final PageResultRepository pageResultRepository;
    private final QualityCriticalErrorRepository qualityCriticalErrorRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final JobProgressReader jobProgressReader;

    /** T1-3 목록 — 최근 hours(기본 24)시간의 전 기관 작업, 10초 폴링용. 휴지통 여부 무관(운영 시야) */
    @Transactional(readOnly = true)
    public AdminMonitorDto.Jobs listJobs(String status, Integer hours, Integer size) {
        if (status != null && !JOB_STATUSES.contains(status)) {
            log.warn("모니터링 상태 필터 오류: {}", status);
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        LocalDateTime since = LocalDateTime.now().minusHours(hours == null ? 24 : Math.min(hours, 24 * 7));
        int limit = size == null ? 100 : Math.min(size, 200);

        List<Job> jobs = status == null
                ? jobRepository.findForMonitoring(since, PageRequest.of(0, limit))
                : jobRepository.findForMonitoringByStatus(since, status, PageRequest.of(0, limit));

        // 종료 작업의 원가 합계 일괄 조회 (진행 중은 화면 규칙상 "—" — null)
        Map<String, Object[]> costs = new HashMap<>();
        List<String> terminalIds = jobs.stream().filter(j -> !IN_FLIGHT.contains(j.getStatus()))
                .map(Job::getId).toList();
        if (!terminalIds.isEmpty()) {
            for (Object[] row : pageResultRepository.costSumsPerJob(terminalIds)) {
                costs.put((String) row[0], row);
            }
        }

        List<AdminMonitorDto.JobRow> items = jobs.stream().map(job -> {
            boolean inFlight = IN_FLIGHT.contains(job.getStatus());
            Object[] cost = costs.get(job.getId());
            return new AdminMonitorDto.JobRow(
                    job.getId(), job.getOriginalFileName(),
                    job.getUser().getOrganization() != null ? job.getUser().getOrganization().getName() : null,
                    job.getUser().getLoginId(), job.getMode(), job.getStatus(), job.getTotalPages(),
                    inFlight ? jobProgressReader.donePages(job.getId()) : null,
                    !inFlight && job.getFailedPages() != null && job.getFailedPages().length > 0
                            ? job.getFailedPages().length : null,
                    cost == null ? null : (BigDecimal) cost[1],
                    cost == null ? null : (Boolean) cost[2],
                    job.getStartedAt(), job.getFinishedAt());
        }).toList();
        return new AdminMonitorDto.Jobs(since, items);
    }

    /** T1-4 상세 — 요청 정보(접속 메타데이터) + 처리·비용 + 쪽별 결과(사유 포함) */
    @Transactional(readOnly = true)
    public AdminMonitorDto.JobDetail getJobDetail(String jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        User user = job.getUser();

        AdminMonitorDto.RequestInfo request = new AdminMonitorDto.RequestInfo(
                user.getLoginId(), user.getAlias(),
                user.getOrganization() != null ? user.getOrganization().getName() : null,
                job.getStartedAt(), job.getClientIp(), job.getClientOs(),
                job.getClientBrowser(), job.getClientUserAgent());

        // 쪽 상태 (pages 테이블이 정본 — RUNNING/PENDING 포함)
        List<Page> pages = pageRepository.findByJobId(jobId).stream()
                .sorted(java.util.Comparator.comparingInt(Page::getPageNo)).toList();

        // 쪽별 결과 — 재시도로 같은 쪽에 결과가 여러 개면 최신 것만
        Map<Integer, PageResult> latestResults = new HashMap<>();
        for (PageResult pr : pageResultRepository.findByJobIdOrderByPageNumber(jobId)) {
            PageResult prev = latestResults.get(pr.getPageNumber());
            if (prev == null || pr.getCreatedAt().isAfter(prev.getCreatedAt())) {
                latestResults.put(pr.getPageNumber(), pr);
            }
        }

        // 실패·검토 사유 (critical_errors 메시지)
        Map<UUID, List<String>> reasons = new HashMap<>();
        if (!latestResults.isEmpty()) {
            List<UUID> resultIds = latestResults.values().stream().map(PageResult::getId).toList();
            for (QualityCriticalError e : qualityCriticalErrorRepository.findByPageResultIdIn(resultIds)) {
                reasons.computeIfAbsent(e.getPageResult().getId(), k -> new ArrayList<>()).add(e.getMessage());
            }
        }

        // 쪽별 차감 크레딧
        Map<Integer, Integer> pageCredits = new HashMap<>();
        long totalCredits = 0;
        for (CreditTransaction tx : creditTransactionRepository.findByJobIdOrderByPageNo(jobId)) {
            pageCredits.put(tx.getPageNo(), tx.getAmount());
            totalCredits += tx.getAmount();
        }

        // 합계·레이아웃 집계
        BigDecimal costKrw = BigDecimal.ZERO, llmUsd = BigDecimal.ZERO, gpuUsd = BigDecimal.ZERO;
        boolean uncertain = false;
        Map<String, Integer> layoutCounts = new LinkedHashMap<>();
        for (PageResult pr : latestResults.values()) {
            if (pr.getCostKrw() != null) costKrw = costKrw.add(pr.getCostKrw());
            if (pr.getLlmCostUsd() != null) llmUsd = llmUsd.add(pr.getLlmCostUsd());
            if (pr.getGpuCostUsd() != null) gpuUsd = gpuUsd.add(pr.getGpuCostUsd());
            uncertain |= pr.isCostUncertain();
            if (pr.getLayoutType() != null) layoutCounts.merge(pr.getLayoutType(), 1, Integer::sum);
        }

        int successPages = (int) pages.stream().filter(p -> PAGE_SUCCESS.contains(p.getStatus())).count();
        int failedPages = (int) pages.stream().filter(p -> "BLOCKED".equals(p.getStatus())).count();

        List<AdminMonitorDto.PageRow> pageRows = pages.stream().map(p -> {
            PageResult pr = latestResults.get(p.getPageNo());
            return new AdminMonitorDto.PageRow(p.getPageNo(), p.getStatus(),
                    pr != null ? pr.getLayoutType() : null,
                    pr != null ? pr.getCostKrw() : null,
                    pageCredits.get(p.getPageNo()),
                    pr != null ? reasons.getOrDefault(pr.getId(), List.of()) : List.of());
        }).toList();

        AdminMonitorDto.ProcessingInfo processing = new AdminMonitorDto.ProcessingInfo(
                job.getTotalPages(), successPages, failedPages,
                job.getStartedAt(), job.getFinishedAt(),
                costKrw, llmUsd, gpuUsd, uncertain, totalCredits, layoutCounts);

        return new AdminMonitorDto.JobDetail(job.getId(), job.getOriginalFileName(), job.getMode(),
                job.getStatus(), request, processing, pageRows);
    }
}

package com.semojum.backend.domain.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** T1-3 실시간 모니터링 · T1-4 작업 상세 (운영자 전용 — 접속 정보 포함, 열람 범위는 계약서 명시 대상) */
public class AdminMonitorDto {

    // ── T1-3 목록 ──
    public record Jobs(LocalDateTime since, List<JobRow> items) {}

    public record JobRow(
            String jobId,
            String fileName,
            String orgName,           // 목록에는 기관만 표시(기획) — 계정은 상세에서
            String loginId,
            String mode,
            String status,            // PENDING(업로드) | IN_PROGRESS | COMPLETED | FAILED
            int totalPages,
            Integer donePages,        // 변환 중일 때만 (Redis, 장애 시 null)
            Integer failedPages,      // 부분 실패 n쪽 (terminal일 때만)
            BigDecimal costKrw,       // 원가 합 — 진행 중이면 null (끝나야 확정)
            Boolean costUncertain,    // 미계상 모델 포함 여부
            LocalDateTime startedAt,
            LocalDateTime finishedAt  // 소요 = finishedAt − startedAt (FE 계산)
    ) {}

    // ── T1-4 상세 ──
    public record JobDetail(
            String jobId,
            String fileName,
            String mode,
            String status,
            RequestInfo request,
            ProcessingInfo processing,
            List<PageRow> pages
    ) {}

    public record RequestInfo(
            String loginId,
            String alias,
            String orgName,
            LocalDateTime requestedAt,
            String clientIp,          // 위치는 표시 시점에 IP로 GeoIP 조회 (BE는 IP만)
            String clientOs,
            String clientBrowser,
            String clientUserAgent
    ) {}

    public record ProcessingInfo(
            int totalPages,
            int successPages,
            int failedPages,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            BigDecimal costKrw,           // 참고값 — 정본은 USD
            BigDecimal llmCostUsd,
            BigDecimal gpuCostUsd,
            boolean costUncertain,
            long credits,                 // 차감 크레딧 합
            Map<String, Integer> layoutCounts   // layout_type별 쪽 수 (예: {"PAGE_LAYOUT_TEXT": 8})
    ) {}

    public record PageRow(
            int pageNo,
            String status,            // COMPLETED | NEEDS_REVIEW | BLOCKED | RUNNING | PENDING
            String layoutType,
            BigDecimal costKrw,
            Integer credit,           // 이 쪽의 차감 크레딧 (기록 없으면 null)
            List<String> reasons      // 실패·검토 사유 (quality critical_errors 메시지)
    ) {}
}

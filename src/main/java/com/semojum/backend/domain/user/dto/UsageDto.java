package com.semojum.backend.domain.user.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** T3 사용량 (점역사 본인) 응답 */
public class UsageDto {

    public record Summary(
            String month,          // "YYYY-MM" (KST)
            long myCredits,        // 해당 월 내가 쓴 크레딧
            Long orgAllocated,     // 기관 할당 총량 (무소속이면 null)
            Long orgUsed,          // 기관 전체 누적 사용 (계정별 분해는 제공하지 않음 — 열람 범위)
            Long orgRemaining
    ) {}

    public record Jobs(
            LocalDate from,
            LocalDate to,
            List<UsageJob> items,
            long totalCredits      // 기간 내 확정(종료) 작업 크레딧 합
    ) {}

    public record UsageJob(
            String jobId,
            String fileName,
            String mode,
            String status,
            int totalPages,
            Integer donePages,     // 변환 중일 때만 (Redis, 장애 시 null)
            Integer failedPages,   // 부분 실패 표기용 (terminal일 때만)
            Long credits,          // 진행 중이면 null — 끝나야 확정
            LocalDateTime finishedAt
    ) {}
}

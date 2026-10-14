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

    /**
     * 마이페이지 [사용량] 화면 (V3.1, 피그마 마이페이지 · 사용량). 단위는 전부 <b>크레딧(차감량)</b>이다 —
     * 화면은 「쪽」이라 적지만 쪽마다 1~5로 가중된 차감량을 뜻한다(2026-10-14 결정).
     *
     * @param from · to         기간 — 오늘 기준. MONTH 이번 달 1일~오늘 · QUARTER 3개월 전~오늘 · YEAR 1월 1일~오늘
     * @param orgAllocated      기관 할당 총량(화면 「전체 2,000쪽」). 무소속이면 null
     * @param orgRemaining      기관 잔여(화면 「남은 쪽 1,418」). 쿠폰 차감은 잔여를 줄이지 않는다
     * @param usedCredits       기간 내 <b>내</b> 사용량(화면 「사용 582쪽」)
     * @param usedPercent       내 사용량 ÷ 기관 할당 총량 × 100, 소수 첫째 자리(화면 「29.1%」). 할당이 없으면 null
     * @param daily             날짜별 내 사용량 — 쓰지 않은 날도 0으로 채운다(막대 간격 유지)
     * @param bySource          입력 종류별 내 사용량 — PDF_HWP · IMAGE · TEXT 세 줄을 늘 보낸다
     */
    public record Daily(
            String period,
            LocalDate from,
            LocalDate to,
            Long orgAllocated,
            Long orgRemaining,
            long usedCredits,
            Double usedPercent,
            List<DailyCredit> daily,
            List<SourceCredit> bySource
    ) {}

    public record DailyCredit(LocalDate date, long credits) {}

    /** @param source PDF_HWP(PDF · HWP · HWPX) · IMAGE(JPG · JPEG · PNG) · TEXT(TXT · 새 문서) */
    public record SourceCredit(String source, long credits) {}
}

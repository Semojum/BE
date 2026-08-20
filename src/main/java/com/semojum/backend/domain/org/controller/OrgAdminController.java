package com.semojum.backend.domain.org.controller;

import com.semojum.backend.domain.org.dto.OrgDto;
import com.semojum.backend.domain.org.service.OrgAdminService;
import com.semojum.backend.global.exception.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * T2 기관 관리 — 기관 관리자(ROLE_ORG_ADMIN) 전용. 권한 검증은 서비스에서(403).
 * 계정 발급·삭제·PW 재발급은 운영자 소관 — 여기는 조회 + 별칭·잠금만.
 */
@RestController
@RequestMapping("/api/org")
@RequiredArgsConstructor
public class OrgAdminController {

    private final OrgAdminService orgAdminService;

    /** 기관 대시보드 — 계약·크레딧(할당/사용/잔여)·월별 사용 추이(최근 6개월) */
    @GetMapping("/dashboard")
    public ApiResponse<OrgDto.Dashboard> getDashboard(@AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(orgAdminService.getDashboard(userDetails.getUsername()));
    }

    /** 소속 계정 목록 — 별칭·상태·마지막 로그인·누적 사용 크레딧(계약 시작일 이후, 기획 확정 2026-08-20) */
    @GetMapping("/accounts")
    public ApiResponse<OrgDto.Accounts> getAccounts(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(orgAdminService.getAccounts(userDetails.getUsername()));
    }

    /** 별칭 지정 — null·빈 문자열이면 제거. 실명 대신 역할명 권장 */
    @PatchMapping("/accounts/{loginId}/alias")
    public ApiResponse<Void> updateAlias(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String loginId,
            @RequestBody @Valid OrgDto.UpdateAlias request
    ) {
        orgAdminService.updateAlias(userDetails.getUsername(), loginId, request.alias());
        return ApiResponse.success(null);
    }

    /** 잠금/해제 — 잠금 즉시 세션 끊김 + 진행 중 변환 취소(완료된 쪽까지만 차감). 본인 잠금 불가 */
    @PatchMapping("/accounts/{loginId}/lock")
    public ApiResponse<OrgDto.LockResult> updateLock(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String loginId,
            @RequestBody @Valid OrgDto.UpdateLock request
    ) {
        return ApiResponse.success(
                orgAdminService.updateLock(userDetails.getUsername(), loginId, request.locked()));
    }

    /** 계정 상세(T2-2) — 기간 내 작업 목록 + 크레딧. 기본 최근 30일. 파일 내용·접속 정보는 없음 */
    @GetMapping("/accounts/{loginId}/jobs")
    public ApiResponse<OrgDto.AccountJobs> getAccountJobs(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String loginId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ApiResponse.success(
                orgAdminService.getAccountJobs(userDetails.getUsername(), loginId, from, to));
    }
}

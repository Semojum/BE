package com.semojum.backend.domain.user.controller;

import com.semojum.backend.domain.user.dto.UsageDto;
import com.semojum.backend.domain.user.service.UsageService;
import com.semojum.backend.global.exception.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** T3 사용량 — 앱 [사용량] 화면. 본인 크레딧 + 기관 잔여(계정별 분해 없음) */
@RestController
@RequestMapping("/api/users/usage")
@RequiredArgsConstructor
public class UsageController {

    private final UsageService usageService;

    /** 월 사용량 요약 — month 미지정 시 이번 달(KST). "지난달" 탭은 month=YYYY-MM */
    @GetMapping
    public ApiResponse<UsageDto.Summary> getSummary(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) String month
    ) {
        return ApiResponse.success(usageService.getSummary(userDetails.getUsername(), month));
    }

    /** 내 작업별 크레딧 — 기본 최근 30일. 진행 중 작업은 크레딧 null(끝나야 확정) */
    @GetMapping("/jobs")
    public ApiResponse<UsageDto.Jobs> getJobs(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ApiResponse.success(usageService.getJobs(userDetails.getUsername(), from, to));
    }
}

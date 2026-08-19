package com.semojum.backend.domain.app.controller;

import com.semojum.backend.domain.app.dto.AppVersionDto;
import com.semojum.backend.domain.app.service.AppVersionService;
import com.semojum.backend.global.exception.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 앱 버전 조회 (무인증) — 데스크톱 앱이 시작 시 호출. result null = 버전 정보 미등록(검사 생략) */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicAppVersionController {

    private final AppVersionService appVersionService;

    @GetMapping("/app-version")
    public ApiResponse<AppVersionDto.Response> getAppVersion() {
        return ApiResponse.success(appVersionService.getCurrent());
    }
}

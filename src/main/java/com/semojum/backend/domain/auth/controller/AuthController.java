package com.semojum.backend.domain.auth.controller;

import com.semojum.backend.domain.auth.dto.AuthRequestDto;
import com.semojum.backend.domain.auth.dto.AuthResponseDto;
import com.semojum.backend.domain.auth.service.AuthService;
import com.semojum.backend.global.exception.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

// V3: 회원가입·소셜 로그인 제거 — 발급형 계정 로그인/로그아웃/재발급만 제공
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // Origin: 브라우저가 자동으로 붙임 — 콘솔 주소면 웹 관리자(admin_scope=WEB) 전용 채널 (V28)
    @PostMapping("/login")
    public ApiResponse<AuthResponseDto.Login> login(
            @RequestBody @Valid AuthRequestDto.Login request,
            @RequestHeader(value = "Origin", required = false) String origin
    ) {
        return ApiResponse.success(authService.login(request, origin));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestBody @Valid AuthRequestDto.Refresh request) {
        authService.logout(request);
        return ApiResponse.success(null);
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthResponseDto.Refresh> refresh(@RequestBody @Valid AuthRequestDto.Refresh request) {
        return ApiResponse.success(authService.refresh(request));
    }
}

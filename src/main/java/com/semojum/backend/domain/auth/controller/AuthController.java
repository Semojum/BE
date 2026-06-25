package com.semojum.backend.domain.auth.controller;

import com.semojum.backend.domain.auth.dto.AuthRequestDto;
import com.semojum.backend.domain.auth.dto.AuthResponseDto;
import com.semojum.backend.domain.auth.service.AuthService;
import com.semojum.backend.global.exception.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    public ApiResponse<AuthResponseDto.SignUp> signUp(@RequestBody @Valid AuthRequestDto.SignUp request) {
        return ApiResponse.success(authService.signUp(request));
    }

    // 이메일 중복확인 (회원가입 전 사용 가능 여부 체크)
    @GetMapping("/email/check")
    public ApiResponse<AuthResponseDto.EmailCheck> checkEmail(@RequestParam(required = false) String email) {
        return ApiResponse.success(authService.checkEmail(email));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponseDto.Login> login(@RequestBody @Valid AuthRequestDto.Login request) {
        return ApiResponse.success(authService.login(request));
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

    @PostMapping("/google")
    public ApiResponse<AuthResponseDto.Login> googleLogin(@RequestBody @Valid AuthRequestDto.GoogleLogin request) {
        return ApiResponse.success(authService.googleLogin(request));
    }

    @PostMapping("/kakao")
    public ApiResponse<AuthResponseDto.Login> kakaoLogin(@RequestBody @Valid AuthRequestDto.KakaoLogin request) {
        return ApiResponse.success(authService.kakaoLogin(request));
    }
}
package com.semojum.backend.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

public class AuthRequestDto {

    // V3: 운영자가 발급한 계정(loginId)으로만 로그인
    public record Login(
            @NotBlank String loginId,
            @NotBlank String password
    ) {}

    public record Refresh(
            @NotBlank String refreshToken
    ) {}
}

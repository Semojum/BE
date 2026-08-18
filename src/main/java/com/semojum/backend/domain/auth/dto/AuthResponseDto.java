package com.semojum.backend.domain.auth.dto;

public class AuthResponseDto {

    public record Login(
            String accessToken,
            String refreshToken,
            String role            // ROLE_ADMIN | ROLE_ORG_ADMIN | ROLE_USER — FE가 화면 입장 판단 (T1 콘솔·T2 탭)
    ) {}

    public record Refresh(
            String accessToken
    ) {}
}

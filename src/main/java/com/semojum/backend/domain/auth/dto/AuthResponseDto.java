package com.semojum.backend.domain.auth.dto;

public class AuthResponseDto {

    public record Login(
            String accessToken,
            String refreshToken
    ) {}

    public record Refresh(
            String accessToken
    ) {}
}

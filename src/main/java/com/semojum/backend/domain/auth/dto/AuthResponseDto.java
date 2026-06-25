package com.semojum.backend.domain.auth.dto;

public class AuthResponseDto {

    public record SignUp(
            String email,
            String name
    ) {}

    public record Login(
            String accessToken,
            String refreshToken
    ) {}

    public record Refresh(
            String accessToken
    ) {}

    public record EmailCheck(
            boolean available
    ) {}
}
package com.semojum.backend.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class AuthRequestDto {

    public record SignUp(
            @Email @NotBlank String email,
            @NotBlank String name,
            @NotBlank String password
    ) {}

    public record Login(
            @Email @NotBlank String email,
            @NotBlank String password
    ) {}

    public record Refresh(
            @NotBlank String refreshToken
    ) {}
}
package com.semojum.backend.domain.admin.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public class AdminRequestDto {

    // 기관 생성
    public record CreateOrg(
            @NotBlank String name,
            LocalDate contractExpiresAt
    ) {}

    // 계정 발급 (1인 1계정, 초기 비밀번호는 서버가 난수 생성)
    public record IssueAccount(
            @NotBlank String organizationId,
            @NotBlank String loginId,
            @NotBlank String name
    ) {}
}

package com.semojum.backend.domain.admin.dto;

import com.semojum.backend.domain.auth.enums.Role;
import com.semojum.backend.domain.auth.enums.UserStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

public class AdminRequestDto {

    // 기관 생성 — code는 계정 loginId 프리픽스 (미입력 시 서버가 orgNN 자동 부여)
    public record CreateOrg(
            @NotBlank String name,
            @Pattern(regexp = "^[a-z][a-z0-9]{1,11}$",
                    message = "기관 코드는 소문자 영숫자 2~12자(첫 글자는 영문)여야 합니다.")
            String code,
            LocalDate contractExpiresAt,
            String contractType        // BASIC|STANDARD|PREMIUM|FREE|COUPON — 미지정 시 FREE (2026-08-20)
    ) {}

    // 계정 일괄 발급 — 서버가 {기관코드}{순번}으로 loginId를 생성 (예: kblib01)
    public record IssueAccounts(
            @NotBlank String organizationId,
            @NotNull @Min(1) @Max(50) Integer count
    ) {}

    // 계정 상태 변경 — INACTIVE로 바꾸면 활성 세션이 전부 revoke된다
    public record UpdateStatus(
            @NotNull UserStatus status
    ) {}

    // 계정 역할 변경 — ROLE_ADMIN(운영·테스트용) / ROLE_USER(점역사)
    public record UpdateRole(
            @NotNull Role role
    ) {}
}

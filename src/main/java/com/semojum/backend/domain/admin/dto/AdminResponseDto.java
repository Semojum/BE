package com.semojum.backend.domain.admin.dto;

public class AdminResponseDto {

    public record Org(
            String organizationId,
            String name
    ) {}

    // 발급/재발급 응답 — 비밀번호는 이 응답에서 1회만 노출 (서버는 BCrypt 해시만 보관)
    public record IssuedAccount(
            String loginId,
            String password
    ) {}
}

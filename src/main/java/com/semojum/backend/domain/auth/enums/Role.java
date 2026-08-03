package com.semojum.backend.domain.auth.enums;

// 계정 역할 — ROLE_ADMIN은 운영·테스트용 계정 분류.
// 현재 운영자 API는 X-Admin-Key로 보호하며(2차 관리자 페이지 전까지),
// 이 role은 Spring Security 권한으로 실려 2차에서 hasRole 기반 전환의 토대가 된다.
public enum Role {
    ROLE_ADMIN, ROLE_USER
}

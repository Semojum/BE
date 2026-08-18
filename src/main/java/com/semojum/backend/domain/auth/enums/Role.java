package com.semojum.backend.domain.auth.enums;

// 계정 역할 — ROLE_ADMIN은 운영·테스트용 계정 분류.
// 운영자 API(/api/admin/**)는 ROLE_ADMIN JWT 전용이며(X-Admin-Key는 2026-08-19 폐기),
// 이 role은 Spring Security 권한으로 실려 2차에서 hasRole 기반 전환의 토대가 된다.
public enum Role {
    ROLE_ADMIN,      // 세모점 운영자 (운영·테스트용 분류, T1 운영자 콘솔)
    ROLE_ORG_ADMIN,  // 기관 관리자 — 앱 로그인 시 "기관 관리"(T2) 접근, 소속 계정 별칭·잠금 제어
    ROLE_USER        // 점역사
}

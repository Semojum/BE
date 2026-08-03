package com.semojum.backend.domain.auth.enums;

// 계정 상태 — INACTIVE면 로그인·토큰 재발급이 차단된다 (계약 만료·퇴사 등 운영자 조치)
public enum UserStatus {
    ACTIVE, INACTIVE
}

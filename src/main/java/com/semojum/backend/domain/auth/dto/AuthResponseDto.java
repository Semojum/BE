package com.semojum.backend.domain.auth.dto;

public class AuthResponseDto {

    public record Login(
            String accessToken,
            String refreshToken,
            String role,           // ROLE_ADMIN | ROLE_ORG_ADMIN | ROLE_USER — FE가 화면 입장 판단 (T1 콘솔·T2 탭)
            String adminScope,     // WEB(콘솔 전용) | APP(에디터 앱용) | null — ROLE_ADMIN 외에는 항상 null (V28)
            // 별칭(users.alias) — 기관 관리자가 붙인 이름표. 앱이 메모 · 답글 작성자로 쓴다(없으면 null → 앱이 loginId로)
            // 메모는 앱이 로컬에서 먼저 만들어 파일에 담으므로 작성 순간 이름을 알아야 한다 (V3.1, 2026-10-21)
            String alias
    ) {}

    public record Refresh(
            String accessToken
    ) {}
}

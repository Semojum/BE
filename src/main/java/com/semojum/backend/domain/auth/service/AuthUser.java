package com.semojum.backend.domain.auth.service;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;

/**
 * 인증 주체 (2026-08-24) — 표준 UserDetails에 loginId를 더한 것.
 * username은 기존 계약대로 유저 UUID 문자열(컨트롤러들이 auth.getName()을 UUID로 파싱한다),
 * loginId는 로그 표기용("REQ … user=testorg01" — UUID 8자는 사람이 못 읽어 교체).
 */
public record AuthUser(String userId, String loginId, String role) implements UserDetails {

    @Override
    public List<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role));
    }

    @Override
    public String getUsername() {
        return userId;
    }

    @Override
    public String getPassword() {
        return "";   // JWT 인증 후 재검증 없음 — 비밀번호 해시를 컨텍스트에 싣지 않는다
    }
}

package com.semojum.backend.global.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 운영자 콘솔(웹사이트)의 Origin 목록 (2026-08-21 — MAC 허용목록 방식 폐기 후 대체).
 *
 * <p>브라우저는 교차 출처 POST에 Origin 헤더를 항상 자동으로 붙인다 — 콘솔에서 온 로그인은
 * Origin이 콘솔 주소이고, 앱(Tauri)은 "null"이라 절대 겹치지 않는다. 이 판별로
 * "웹사이트 로그인은 웹 관리자(admin_scope=WEB)만"을 FE 수정·사용자 입력 없이 강제한다.
 *
 * <p>콘솔 주소가 바뀌면 EC2 .env의 ADMIN_CONSOLE_ORIGINS로 교체(콤마 구분, 스킴 포함).
 */
@Component
public class ConsoleOrigins {

    private final Set<String> origins;

    public ConsoleOrigins(@Value("${admin.console-origins:}") String raw) {
        this.origins = Arrays.stream(raw.split(","))
                .map(ConsoleOrigins::normalize)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 이 Origin이 운영자 콘솔인가 — null·"null"(앱)·미등록 주소는 전부 false */
    public boolean isConsole(String origin) {
        if (origin == null) return false;
        String normalized = normalize(origin);
        return !normalized.isEmpty() && origins.contains(normalized);
    }

    // 대소문자·끝 슬래시 편차 흡수
    static String normalize(String origin) {
        String s = origin.trim().toLowerCase();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}

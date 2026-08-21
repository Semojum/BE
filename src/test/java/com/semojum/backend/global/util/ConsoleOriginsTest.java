package com.semojum.backend.global.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleOriginsTest {

    @Test
    void 콘솔_주소만_참이고_앱과_미등록_주소는_거짓이다() {
        ConsoleOrigins origins = new ConsoleOrigins("http://54.116.113.4, https://admin.semo-jum.com");

        assertTrue(origins.isConsole("http://54.116.113.4"));
        assertTrue(origins.isConsole("https://admin.semo-jum.com"));
        assertTrue(origins.isConsole("https://ADMIN.semo-jum.com/"));   // 대소문자·끝 슬래시 흡수

        assertFalse(origins.isConsole(null));          // curl 등 헤더 없음
        assertFalse(origins.isConsole("null"));        // Tauri 앱
        assertFalse(origins.isConsole("https://evil.example.com"));
    }

    @Test
    void 미설정이면_어떤_Origin도_콘솔이_아니다() {
        ConsoleOrigins empty = new ConsoleOrigins("");
        assertFalse(empty.isConsole("http://54.116.113.4"));
    }
}

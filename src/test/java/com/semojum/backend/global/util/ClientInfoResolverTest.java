package com.semojum.backend.global.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

// 접속 메타데이터 추출 규칙 — IP 헤더 우선순위 + UA 간이 파싱
class ClientInfoResolverTest {

    private final ClientInfoResolver resolver = new ClientInfoResolver();

    private HttpServletRequest req(String cfIp, String xff, String remote, String ua) {
        HttpServletRequest r = Mockito.mock(HttpServletRequest.class);
        when(r.getHeader("CF-Connecting-IP")).thenReturn(cfIp);
        when(r.getHeader("X-Forwarded-For")).thenReturn(xff);
        when(r.getRemoteAddr()).thenReturn(remote);
        when(r.getHeader("User-Agent")).thenReturn(ua);
        return r;
    }

    @Test
    void IP는_CF헤더_XFF_remoteAddr_순() {
        assertEquals("210.94.1.118",
                resolver.resolve(req("210.94.1.118", "10.0.0.1, 172.16.0.1", "172.31.0.5", null)).ip());
        assertEquals("10.0.0.1",
                resolver.resolve(req(null, "10.0.0.1, 172.16.0.1", "172.31.0.5", null)).ip());
        assertEquals("172.31.0.5",
                resolver.resolve(req(null, null, "172.31.0.5", null)).ip());
    }

    @Test
    void 윈도우_크롬_파싱() {
        var info = resolver.resolve(req(null, null, "1.2.3.4",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"));
        assertEquals("Windows", info.os());
        assertEquals("Chrome 141", info.browser());
    }

    @Test
    void 데스크톱_앱은_Electron으로_판정() {
        var info = resolver.resolve(req(null, null, "1.2.3.4",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 semojum/1.0 Chrome/120.0.0.0 Electron/28.1.0 Safari/537.36"));
        assertEquals("Windows", info.os());
        assertEquals("Electron 28", info.browser());
    }

    @Test
    void 맥_사파리와_엣지() {
        var safari = resolver.resolve(req(null, null, "1.2.3.4",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15"));
        assertEquals("macOS", safari.os());
        assertEquals("Safari 17", safari.browser());

        var edge = resolver.resolve(req(null, null, "1.2.3.4",
                "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36 Edg/122.0.2365.92"));
        assertEquals("Edge 122", edge.browser());
    }

    @Test
    void UA_없으면_null_저장() {
        var info = resolver.resolve(req(null, null, "1.2.3.4", null));
        assertNull(info.os());
        assertNull(info.browser());
        assertNull(info.userAgent());
        assertEquals("1.2.3.4", info.ip());
    }

    @Test
    void 타우리_앱_UA는_앱으로_판정하고_OS는_헤더가_담당() {
        ClientInfoResolver r = new ClientInfoResolver();
        assertEquals("세모점 앱 (Tauri 2.5.8)", r.parseBrowser("tauri-plugin-http/2.5.8"));
        assertNull(r.parseOs("tauri-plugin-http/2.5.8"));   // UA에 OS 없음 — X-Client-Os 헤더로 보완
    }
}

package com.semojum.backend.global.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 요청 헤더에서 접속 메타데이터(IP·OS·브라우저)를 추출한다 — T1-4 "요청 정보"의 원천.
 * IP는 Cloudflare → Envoy → 앱 경유이므로 CF-Connecting-IP > X-Forwarded-For 첫 항목 > remoteAddr 순.
 * UA 파싱은 화면 표시 수준("Windows · Chrome 141")의 간이 규칙 — 원본 UA도 함께 저장하므로
 * 규칙이 바뀌어도 과거 기록을 재해석할 수 있다.
 */
@Component
public class ClientInfoResolver {

    public record ClientInfo(String ip, String os, String browser, String userAgent) {}

    private static final Pattern EDGE = Pattern.compile("Edg(?:e|A|iOS)?/(\\d+)");
    private static final Pattern CHROME = Pattern.compile("Chrome/(\\d+)");
    private static final Pattern FIREFOX = Pattern.compile("Firefox/(\\d+)");
    private static final Pattern SAFARI_VER = Pattern.compile("Version/(\\d+)[.\\d]*.*Safari");
    private static final Pattern ELECTRON = Pattern.compile("Electron/(\\d+)");
    private static final Pattern ANDROID = Pattern.compile("Android (\\d+)");
    private static final Pattern IOS = Pattern.compile("(?:iPhone|iPad|CPU) OS (\\d+)");

    public ClientInfo resolve(HttpServletRequest request) {
        String ip = resolveIp(request);
        String ua = request.getHeader("User-Agent");
        String uaStored = ua == null ? null : ua.length() > 300 ? ua.substring(0, 300) : ua;
        return new ClientInfo(ip, parseOs(ua), parseBrowser(ua), uaStored);
    }

    private String resolveIp(HttpServletRequest request) {
        String cf = request.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) return truncate(cf.trim());
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return truncate(xff.split(",")[0].trim());
        return truncate(request.getRemoteAddr());
    }

    private String truncate(String s) {
        return s == null ? null : s.length() > 45 ? s.substring(0, 45) : s;
    }

    String parseOs(String ua) {
        if (ua == null || ua.isBlank()) return null;
        Matcher android = ANDROID.matcher(ua);
        if (android.find()) return "Android " + android.group(1);
        Matcher ios = IOS.matcher(ua);
        if (ios.find()) return "iOS " + ios.group(1);
        if (ua.contains("Windows NT")) return "Windows";      // NT 10.0은 10/11 구분 불가 — UA 한계
        if (ua.contains("Mac OS X")) return "macOS";
        if (ua.contains("Linux")) return "Linux";
        return null;
    }

    String parseBrowser(String ua) {
        if (ua == null || ua.isBlank()) return null;
        // 데스크톱 앱(Electron)이 Chrome 토큰도 포함하므로 먼저 판정
        Matcher electron = ELECTRON.matcher(ua);
        if (electron.find()) return "Electron " + electron.group(1);
        Matcher edge = EDGE.matcher(ua);
        if (edge.find()) return "Edge " + edge.group(1);
        Matcher chrome = CHROME.matcher(ua);
        if (chrome.find()) return "Chrome " + chrome.group(1);
        Matcher firefox = FIREFOX.matcher(ua);
        if (firefox.find()) return "Firefox " + firefox.group(1);
        Matcher safari = SAFARI_VER.matcher(ua);
        if (safari.find()) return "Safari " + safari.group(1);
        return null;
    }
}

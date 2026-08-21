package com.semojum.backend.global.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 웹 관리자(admin_scope=WEB) 로그인 허용 기기 목록 — MAC 주소 기반 (V28).
 *
 * <p>브라우저는 기기의 MAC을 스스로 읽을 수 없으므로, 운영자 콘솔 FE가 사용자에게 입력받아
 * 로그인 요청의 X-Device-Mac 헤더로 보낸다. 네트워크 계층 검증이 아니라 "등록된 값을 아는
 * 기기만 통과"하는 2차 인증(비밀번호+기기 값) 성격 — 목록 관리는 EC2 .env의 ADMIN_ALLOWED_MACS.
 *
 * <p>미설정(빈 목록)이면 웹 관리자 로그인 전면 거부(fail-safe) — 보안 기능은 열린 채 실패하지 않는다.
 */
@Component
public class AdminMacAllowlist {

    private final Set<String> allowed;

    public AdminMacAllowlist(@Value("${admin.allowed-macs:}") String macs) {
        this.allowed = Arrays.stream(macs.split(","))
                .map(AdminMacAllowlist::normalize)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAllowed(String mac) {
        if (mac == null) return false;
        String normalized = normalize(mac);
        return !normalized.isEmpty() && allowed.contains(normalized);
    }

    // 표기 편차 흡수 — 대소문자·구분자(하이픈/콜론) 통일
    static String normalize(String mac) {
        return mac.trim().toLowerCase().replace('-', ':');
    }
}

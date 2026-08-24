package com.semojum.backend.global.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 요청당 결과 한 줄(액세스 로그): {@code REQ POST /api/jobs → 200 (2610ms) user=xxxxxxxx}.
 * 장애 대응의 출발점 — "몇 시에 누가 어떤 API를 호출해 몇 번 코드로 얼마 만에 끝났나"를 보장한다.
 *
 * <p>MDC ctx에 요청 ID(req-xxxxxxxx)를 심어 이 요청이 남긴 모든 로그를 grep 하나로 묶는다.
 * 레벨은 결과가 정한다: 2xx·3xx=INFO, 4xx=WARN(봇·실수 — 일상), 5xx=ERROR(진짜 장애).
 *
 * <p>순서 -99: 시큐리티 필터 체인(-100) 바로 안쪽 — JwtFilter가 심은 인증 정보를 읽을 수 있고,
 * 시큐리티가 컨텍스트를 지우기 전에 finally가 실행된다. 시큐리티 단계에서 거절된 요청(401)은
 * 여기까지 오지 않으며 JwtFilter·entryPoint가 각자 WARN 한 줄을 남긴다.
 */
@Slf4j
@Component
@Order(-99)
public class RequestLogFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // 문서·아이콘 요청은 운영 신호가 아니라서 제외
        // /api/health는 Envoy가 3초마다 호출 — REQ 로그로 쌓이면 그게 다시 노이즈
        return uri.startsWith("/swagger-ui") || uri.startsWith("/v3/api-docs")
                || uri.equals("/favicon.ico") || uri.equals("/api/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        long start = System.currentTimeMillis();
        // 시큐리티 체인(-100)이 먼저라 토큰 인증은 이미 끝난 시점 — ctx에 유저를 바로 실어
        // 이 요청의 모든 로그 줄이 req-xxxxxxxx|loginId 로 찍히게 한다 (2026-08-24)
        String ctx = "req-" + Integer.toHexString(ThreadLocalRandom.current().nextInt());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal()
                instanceof com.semojum.backend.domain.auth.service.AuthUser authUser) {
            ctx += "|" + authUser.loginId();
        }
        MDC.put("ctx", ctx);
        try {
            filterChain.doFilter(request, response);
        } finally {
            long tookMs = System.currentTimeMillis() - start;
            int status = response.getStatus();
            String line = String.format("REQ %s %s → %d (%dms) user=%s",
                    request.getMethod(), request.getRequestURI(), status, tookMs, currentUser());
            if (status >= 500) {
                log.error(line);
            } else if (status >= 400) {
                log.warn(line);
            } else {
                log.info(line);
            }
            MDC.remove("ctx");
            MDC.remove("user");
        }
    }

    /**
     * 인증된 요청의 사용자 아이디(loginId — UUID 8자는 사람이 못 읽어 교체, 2026-08-24).
     * 토큰 없는 로그인·refresh·logout은 서비스가 유저를 알아낸 시점에 MDC user로 채운다. 그 외 "-"
     */
    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            if (auth.getPrincipal() instanceof com.semojum.backend.domain.auth.service.AuthUser authUser) {
                return authUser.loginId();
            }
            String name = auth.getName();
            return name.length() > 8 ? name.substring(0, 8) : name;
        }
        String mdcUser = MDC.get("user");
        return mdcUser != null ? mdcUser : "-";
    }
}

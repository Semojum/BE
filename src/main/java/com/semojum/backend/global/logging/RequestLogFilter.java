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
        return uri.startsWith("/swagger-ui") || uri.startsWith("/v3/api-docs") || uri.equals("/favicon.ico");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        long start = System.currentTimeMillis();
        MDC.put("ctx", "req-" + Integer.toHexString(ThreadLocalRandom.current().nextInt()));
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
        }
    }

    /** 인증된 요청의 사용자 식별자(UUID 앞 8자). 미인증·permitAll 경로는 "-" */
    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return "-";
        }
        String name = auth.getName();
        return name.length() > 8 ? name.substring(0, 8) : name;
    }
}

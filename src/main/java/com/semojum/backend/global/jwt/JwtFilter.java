package com.semojum.backend.global.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semojum.backend.global.exception.ApiResponse;
import com.semojum.backend.global.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class JwtFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final UserDetailsService userDetailsService;
    private final ObjectMapper objectMapper;

    // 인증 없이 통과시킬 경로
    private static final List<String> PERMIT_URLS = List.of(
            "/api/auth/login",
            // refresh/logout은 액세스 토큰이 만료된 뒤에 호출되므로 액세스 토큰 검사를 하면 안 된다.
            // 두 API 모두 요청 본문의 refreshToken 유효성 + 세션(revoked_at) 상태로 자체 검증한다.
            "/api/auth/refresh",
            "/api/auth/logout",
            "/api/admin/",      // 운영자 API — X-Admin-Key로 자체 검증
            "/api/health",      // 헬스체크 — Envoy·deploy.sh가 토큰 없이 호출
            "/api/public/",     // 홈페이지 공개 접수(문의) — 무인증, 남용 방어는 서비스 계층(허니팟·레이트리밋)
            "/swagger-ui",
            "/v3/api-docs"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String requestUri = request.getRequestURI();

        // permitAll 경로는 토큰이 없어도 통과하되, 토큰이 있으면 인증을 실어준다(best-effort).
        // — 운영자 API(/api/admin/)가 JWT(ROLE_ADMIN)로도 통과하려면 permitted 경로에서도
        //   SecurityContext에 권한이 실려 있어야 한다 (실패해도 401 내지 않고 그냥 무인증 통과)
        boolean isPermitted = PERMIT_URLS.stream().anyMatch(requestUri::startsWith);
        if (isPermitted) {
            String optionalToken = resolveToken(request);
            if (optionalToken != null && jwtProvider.isValid(optionalToken)) {
                try {
                    UserDetails details = userDetailsService.loadUserByUsername(jwtProvider.getUserId(optionalToken));
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
                } catch (Exception e) {
                    log.debug("permitted 경로 best-effort 인증 실패(무인증 통과): {}", e.getMessage());
                }
            }
            filterChain.doFilter(request, response);
            return;
        }

        String token = resolveToken(request);

        // 토큰 없음
        if (token == null) {
            sendUnauthorized(request, response, "토큰 없음");
            return;
        }

        // 토큰 유효하지 않음
        if (!jwtProvider.isValid(token)) {
            sendUnauthorized(request, response, "토큰 무효·만료");
            return;
        }

        String userId = jwtProvider.getUserId(token);
        UserDetails userDetails = userDetailsService.loadUserByUsername(userId);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        filterChain.doFilter(request, response);
    }

    // 미인증은 일상(봇·만료 토큰) — 스택 없이 WARN 한 줄. 진짜 장애(ERROR)가 묻히지 않게 한다
    private void sendUnauthorized(HttpServletRequest request, HttpServletResponse response, String reason)
            throws IOException {
        log.warn("REQ {} {} → 401 ({})", request.getMethod(), request.getRequestURI(), reason);
        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.failure(ErrorCode.COMMON_UNAUTHORIZED));
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
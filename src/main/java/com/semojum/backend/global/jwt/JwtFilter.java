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
public class JwtFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final UserDetailsService userDetailsService;
    private final ObjectMapper objectMapper;

    // 인증 없이 통과시킬 경로
    private static final List<String> PERMIT_URLS = List.of(
            "/api/auth/login",
            "/api/admin/",      // 운영자 API — X-Admin-Key로 자체 검증
            "/swagger-ui",
            "/v3/api-docs"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String requestUri = request.getRequestURI();

        // permitAll 경로는 토큰 검사 없이 통과
        boolean isPermitted = PERMIT_URLS.stream().anyMatch(requestUri::startsWith);
        if (isPermitted) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = resolveToken(request);

        // 토큰 없음
        if (token == null) {
            sendUnauthorized(response);
            return;
        }

        // 토큰 유효하지 않음
        if (!jwtProvider.isValid(token)) {
            sendUnauthorized(response);
            return;
        }

        String userId = jwtProvider.getUserId(token);
        UserDetails userDetails = userDetailsService.loadUserByUsername(userId);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        filterChain.doFilter(request, response);
    }

    private void sendUnauthorized(HttpServletResponse response) throws IOException {
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
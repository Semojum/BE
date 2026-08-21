package com.semojum.backend.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semojum.backend.global.exception.ApiResponse;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.jwt.JwtFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    // CORS origin 패턴 (쉼표 구분). 기본 * — 쿠키 미사용(Bearer 토큰) 구조라 제한해도 얻는 보안이 없고,
    // 제한하면 데스크톱 앱(Electron, Origin: null·file 등)이 깨진다
    @org.springframework.beans.factory.annotation.Value("${cors.allowed-origins:*}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // refresh/logout은 refreshToken으로 자체 검증하므로 액세스 토큰을 요구하지 않는다
                        .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                        // 운영자 API — JWT(ROLE_ADMIN) 전용 (X-Admin-Key는 2026-08-19 폐기)
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // 헬스체크 — Envoy·배포 스크립트가 무인증으로 호출
                        .requestMatchers("/api/health").permitAll()
                        // 홈페이지 공개 접수 — 무인증 (남용 방어는 서비스 계층)
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // 에러 디스패치(/error 포워드)까지 인가를 요구하면 예외 1건이
                        // AuthorizationDeniedException 스택 수백 줄로 증폭된다(2026-08-10 실측) — 반드시 허용
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, authException) -> {
                            // 미인증 접근은 일상(봇·만료 토큰) — 스택 없이 WARN 한 줄만
                            log.warn("인증 거부: {} {} → 401", request.getMethod(), request.getRequestURI());
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            new ObjectMapper().writeValue(
                                    response.getWriter(),
                                    ApiResponse.failure(ErrorCode.COMMON_UNAUTHORIZED)
                            );
                        })
                        // 인증은 됐지만 역할이 부족한 경우(예: 점역사 토큰으로 /api/admin/**) — 403 JSON
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            log.warn("인가 거부: {} {} → 403", request.getMethod(), request.getRequestURI());
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            new ObjectMapper().writeValue(
                                    response.getWriter(),
                                    ApiResponse.failure(ErrorCode.COMMON_FORBIDDEN)
                            );
                        })
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // T1 콘솔 브라우저 fetch용 CORS. JWT는 Authorization 헤더로 실리고 쿠키를 안 쓰므로(allowCredentials 없음)
    // origin 제한이 보안상 의미가 없다 — 기본 전면 허용. ⚠️ 허용 목록으로 좁히면 Electron 앱(Origin: null 등)의
    // 로그인이 "Invalid CORS request" 403으로 깨진다 (2026-08-20 실측 회귀). 좁힐 일이 생기면 앱 origin까지 포함할 것.
    @Bean
    public org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
        var config = new org.springframework.web.cors.CorsConfiguration();
        config.setAllowedOriginPatterns(java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // X-Device-Mac: 웹 관리자 로그인 기기 검증(V28) / X-Client-Os: 앱 접속환경(V19) — 브라우저 preflight 통과용
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Device-Mac", "X-Client-Os"));
        config.setMaxAge(3600L);
        var source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
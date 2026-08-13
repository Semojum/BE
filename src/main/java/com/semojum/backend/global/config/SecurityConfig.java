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

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // refresh/logout은 refreshToken으로 자체 검증하므로 액세스 토큰을 요구하지 않는다
                        .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                        // 운영자 API는 JWT 대신 X-Admin-Key 헤더로 자체 검증 (AdminController)
                        .requestMatchers("/api/admin/**").permitAll()
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
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
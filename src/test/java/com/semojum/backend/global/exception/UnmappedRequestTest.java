package com.semojum.backend.global.exception;

import com.semojum.backend.global.grpc.BrailleGrpcClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 매핑되지 않은 요청이 500이 아니라 올바른 상태 코드로 나가는지 검증한다.
 *
 * <p>GlobalExceptionHandler의 catch-all이 Spring MVC의 "핸들러 없음" 예외까지 삼켜
 * 오타 난 경로도 COMMON5000으로 응답하던 회귀를 막는다.
 *
 * <p>인증을 거치지 않고 디스패처까지 도달시키려고 JwtFilter의 PERMIT_URLS에 있는
 * {@code /api/public/}·{@code /api/auth/} 하위 경로를 사용한다
 * (/api/admin/은 2026-08-19부터 JWT 필수라 무인증 접근이 401로 먼저 끊긴다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "JWT_SECRET=test-secret-key-for-context-load-only-32bytes+",
        "DB_PASSWORD=test",
        "GRPC_CERT_PATH=classpath:grpc/test-server.crt"
})
class UnmappedRequestTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @MockitoBean
    BrailleGrpcClient brailleGrpcClient;

    @Autowired
    MockMvc mockMvc;

    @Test
    void 존재하지_않는_경로는_404를_준다() throws Exception {
        mockMvc.perform(get("/api/public/이런건없음"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON4004"));
    }

    @Test
    void 경로는_있으나_메서드가_다르면_405를_준다() throws Exception {
        // /api/auth/login 은 POST만 존재한다
        mockMvc.perform(delete("/api/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("COMMON4005"));
    }
}

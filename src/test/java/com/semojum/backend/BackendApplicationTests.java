package com.semojum.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 애플리케이션 컨텍스트가 실제로 뜨는지 검증한다(빈 배선 회귀 방지).
 *
 * <p>DB·Redis는 Testcontainers로 띄운다 — 예전에는 설정의 기본 DB 주소로 붙으려다
 * 로컬에서 항상 실패했다. 스키마는 엔티티 기준으로 만들고 Flyway는 끈다
 * (마이그레이션은 운영 스키마를 전제로 하므로 빈 DB에 그대로 적용되지 않는다).
 *
 * <p>AI 서버 gRPC는 테스트 인증서(src/test/resources/grpc)로 AiServerPool을 실제로 띄운다.
 * 채널은 첫 RPC 전까지 연결하지 않으므로 AI 서버 없이도 안전하다.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        // 운영에서 환경변수로 주입되는 값들 — 컨텍스트 기동에만 필요한 더미
        "JWT_SECRET=test-secret-key-for-context-load-only-32bytes+",
        "DB_PASSWORD=test",
        "GRPC_CERT_PATH=classpath:grpc/test-server.crt",
        "ADMIN_API_KEY=test-key"
})
class BackendApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Test
    void contextLoads() {
    }
}

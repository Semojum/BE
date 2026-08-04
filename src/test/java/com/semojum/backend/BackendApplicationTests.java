package com.semojum.backend;

import com.semojum.backend.global.grpc.BrailleGrpcClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
 * <p>AI 서버 gRPC 클라이언트는 목으로 대체한다. 실제 통신을 하지 않는 테스트인데도
 * 채널이 기동 시 TLS 인증서를 요구해, 저장소에 쓰지도 않는 인증서 파일을 두게 되기 때문이다.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        // 운영에서 환경변수로 주입되는 값들 — 컨텍스트 기동에만 필요한 더미
        "JWT_SECRET=test-secret-key-for-context-load-only-32bytes+",
        "DB_PASSWORD=test",
        "GRPC_CERT_PATH=",
        "grpc.client.ai-server.negotiation-type=PLAINTEXT",
        "grpc.client.ai-server.security.enabled=false",
        "GOOGLE_CLIENT_ID=test", "GOOGLE_CLIENT_SECRET=test",
        "KAKAO_CLIENT_ID=test", "KAKAO_CLIENT_SECRET=test",
        "ADMIN_API_KEY=test-key"
})
class BackendApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    // 실제 AI 서버에 붙지 않는다 — 채널·인증서 없이 컨텍스트만 검증
    @MockitoBean
    BrailleGrpcClient brailleGrpcClient;

    @Test
    void contextLoads() {
    }
}

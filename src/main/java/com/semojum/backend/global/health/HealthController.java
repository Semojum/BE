package com.semojum.backend.global.health;

import com.semojum.backend.global.exception.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 살아있음 확인 — 블루그린 배포의 두 소비자가 쓴다:
 * ① Envoy 액티브 헬스체크(3초 주기)가 트래픽을 보낼 색을 판정
 * ② deploy.sh가 새 색을 띄운 뒤 이 응답을 보고 전환 여부를 결정 (실패 시 구버전 유지)
 *
 * <p>인증 없음(JwtFilter PERMIT_URLS + SecurityConfig permitAll) — 헬스체크는 토큰이 없다.
 * RequestLogFilter에서 제외 — 3초마다 REQ 로그가 쌓이면 그게 다시 노이즈가 된다.
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.success(Map.of("status", "UP"));
    }
}

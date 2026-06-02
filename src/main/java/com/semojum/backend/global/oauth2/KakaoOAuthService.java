package com.semojum.backend.global.oauth2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KakaoOAuthService {

    @Value("${spring.security.oauth2.client.registration.kakao.client-id}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.kakao.client-secret}")
    private String clientSecret;

    private final RestTemplate restTemplate = new RestTemplate();

    public Map<String, Object> exchange(String code, String codeVerifier, String redirectUri) {
        try {
            // 카카오 token endpoint에 code + code_verifier로 토큰 교환
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("grant_type", "authorization_code");
            params.add("client_id", clientId);
            params.add("client_secret", clientSecret);
            params.add("code", code);
            params.add("redirect_uri", redirectUri);
            params.add("code_verifier", codeVerifier);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

            ResponseEntity<Map> tokenResponse = restTemplate.postForEntity(
                    "https://kauth.kakao.com/oauth/token",
                    request,
                    Map.class
            );

            String accessToken = (String) tokenResponse.getBody().get("access_token");

            // 카카오 유저 정보 조회
            HttpHeaders userHeaders = new HttpHeaders();
            userHeaders.setBearerAuth(accessToken);
            HttpEntity<Void> userRequest = new HttpEntity<>(userHeaders);

            ResponseEntity<Map> userResponse = restTemplate.exchange(
                    "https://kapi.kakao.com/v2/user/me",
                    HttpMethod.GET,
                    userRequest,
                    Map.class
            );

            return userResponse.getBody();

        } catch (Exception e) {
            log.error("카카오 OAuth2 토큰 교환 실패: {}", e.getMessage());
            throw new RuntimeException("카카오 로그인 처리 중 오류가 발생했습니다.");
        }
    }
}
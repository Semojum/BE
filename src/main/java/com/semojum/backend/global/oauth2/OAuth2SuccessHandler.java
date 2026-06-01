package com.semojum.backend.global.oauth2;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.entity.UserSession;
import com.semojum.backend.domain.auth.enums.AuthProvider;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.auth.repository.UserSessionRepository;
import com.semojum.backend.global.jwt.JwtProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();

        // 어떤 provider인지 확인
        String registrationId = ((OAuth2AuthenticationToken) authentication).getAuthorizedClientRegistrationId();
        AuthProvider provider = AuthProvider.valueOf(registrationId.toUpperCase());

        Map<String, Object> attributes = oAuth2User.getAttributes();

        // provider별 고유 ID 추출
        String providerUid;
        if (provider == AuthProvider.KAKAO) {
            providerUid = String.valueOf(attributes.get("id"));
        } else {
            providerUid = String.valueOf(attributes.get("sub"));
        }

        // DB에서 유저 조회
        User user = userRepository.findByProviderAndProviderUid(provider, providerUid)
                .orElseThrow();

        // JWT 발급 (subject: UUID)
        String accessToken = jwtProvider.generateAccessToken(user.getId().toString());
        String refreshToken = jwtProvider.generateRefreshToken(user.getId().toString());

        // 리프레시 토큰 세션 저장
        String hash = jwtProvider.hashToken(refreshToken);
        LocalDateTime expiresAt = LocalDateTime.now()
                .plusSeconds(jwtProvider.getRefreshTokenExpiry() / 1000);

        UserSession session = UserSession.builder()
                .user(user)
                .refreshTokenHash(hash)
                .expiresAt(expiresAt)
                .createdAt(LocalDateTime.now())
                .build();

        userSessionRepository.save(session);

        // 프론트엔드로 토큰 전달 (임시 URL, FE 방식 확정 후 수정 필요)
        String redirectUrl = "http://localhost:3000/oauth2/callback"
                + "?accessToken=" + accessToken
                + "&refreshToken=" + refreshToken;

        getRedirectStrategy().sendRedirect(request, response, redirectUrl);
    }
}
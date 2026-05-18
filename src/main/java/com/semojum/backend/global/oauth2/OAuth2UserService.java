package com.semojum.backend.global.oauth2;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.enums.AuthProvider;
import com.semojum.backend.domain.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class OAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);

        // 어떤 provider인지 확인 (kakao, google)
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        AuthProvider provider = AuthProvider.valueOf(registrationId.toUpperCase());

        String providerUid;
        String email;
        String name;

        if (provider == AuthProvider.KAKAO) {
            // 카카오 응답 파싱
            Map<String, Object> attributes = oAuth2User.getAttributes();
            Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
            Map<String, Object> profile = (Map<String, Object>) kakaoAccount.get("profile");

            providerUid = String.valueOf(attributes.get("id"));
            email = (String) kakaoAccount.get("email");
            name = (String) profile.get("nickname");

        } else if (provider == AuthProvider.GOOGLE) {
            // 구글 응답 파싱
            Map<String, Object> attributes = oAuth2User.getAttributes();

            providerUid = String.valueOf(attributes.get("sub")); // 구글 고유 ID
            email = (String) attributes.get("email");
            name = (String) attributes.get("name");

        } else {
            throw new OAuth2AuthenticationException("지원하지 않는 소셜 로그인입니다: " + registrationId);
        }

        // DB에 없으면 새로 저장, 있으면 그냥 반환
        userRepository.findByProviderAndProviderUid(provider, providerUid)
                .orElseGet(() -> userRepository.save(
                        User.builder()
                                .email(email)
                                .name(name)
                                .provider(provider)
                                .providerUid(providerUid)
                                .build()
                ));

        return oAuth2User;
    }
}
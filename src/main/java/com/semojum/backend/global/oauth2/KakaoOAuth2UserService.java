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
public class KakaoOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);

        Map<String, Object> attributes = oAuth2User.getAttributes();
        Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
        Map<String, Object> profile = (Map<String, Object>) kakaoAccount.get("profile");

        String providerUid = String.valueOf(attributes.get("id"));
        String nickname = (String) profile.get("nickname");
        String email = (String) kakaoAccount.get("email");

        // DB에 없으면 새로 저장, 있으면 그냥 반환
        userRepository.findByProviderAndProviderUid(AuthProvider.KAKAO, providerUid)
                .orElseGet(() -> userRepository.save(
                        User.builder()
                                .email(email)
                                .name(nickname)
                                .provider(AuthProvider.KAKAO)
                                .providerUid(providerUid)
                                .build()
                ));

        return oAuth2User;
    }
}
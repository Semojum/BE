package com.semojum.backend.domain.auth.service;

import com.semojum.backend.domain.auth.dto.AuthRequestDto;
import com.semojum.backend.domain.auth.dto.AuthResponseDto;
import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.entity.UserSession;
import com.semojum.backend.domain.auth.enums.AuthProvider;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.auth.repository.UserSessionRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final JwtProvider jwtProvider;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public AuthResponseDto.SignUp signUp(AuthRequestDto.SignUp request) {
        // 이메일 중복 체크
        if (userRepository.existsByEmail(request.email())) {
            throw new CustomException(ErrorCode.AUTH_DUPLICATE_EMAIL);
        }

        // 일반 로그인 유저 생성 (provider = EMAIL)
        User user = User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .name(request.name())
                .provider(AuthProvider.EMAIL)
                .build();

        userRepository.save(user);

        return new AuthResponseDto.SignUp(user.getEmail(), user.getName());
    }

    @Transactional
    public AuthResponseDto.Login login(AuthRequestDto.Login request) {
        // 이메일로 유저 조회
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new CustomException(ErrorCode.AUTH_INVALID_CREDENTIALS));

        // 비밀번호 검증
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new CustomException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        // JWT 발급 (subject: UUID)
        String accessToken = jwtProvider.generateAccessToken(user.getId().toString());
        String refreshToken = jwtProvider.generateRefreshToken(user.getId().toString());

        // 리프레시 토큰 세션 저장
        saveSession(user, refreshToken);

        return new AuthResponseDto.Login(accessToken, refreshToken);
    }

    @Transactional
    public void logout(AuthRequestDto.Refresh request) {
        String refreshToken = request.refreshToken();

        // 유효성 검증
        if (!jwtProvider.isValid(refreshToken)) {
            throw new CustomException(ErrorCode.AUTH_INVALID_TOKEN);
        }

        // 유저 조회
        String userId = jwtProvider.getUserId(refreshToken);
        User user = userRepository.findById(java.util.UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 세션 조회 후 revoke
        String hash = jwtProvider.hashToken(refreshToken);
        UserSession session = userSessionRepository
                .findByUserAndRefreshTokenHashAndRevokedAtIsNull(user, hash)
                .orElseThrow(() -> new CustomException(ErrorCode.AUTH_INVALID_TOKEN));

        session.revoke();
    }

    @Transactional
    public AuthResponseDto.Refresh refresh(AuthRequestDto.Refresh request) {
        String refreshToken = request.refreshToken();

        // 유효성 검증
        if (!jwtProvider.isValid(refreshToken)) {
            throw new CustomException(ErrorCode.AUTH_INVALID_TOKEN);
        }

        // 유저 조회
        String userId = jwtProvider.getUserId(refreshToken);
        User user = userRepository.findById(java.util.UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 세션 유효성 확인
        String hash = jwtProvider.hashToken(refreshToken);
        UserSession session = userSessionRepository
                .findByUserAndRefreshTokenHashAndRevokedAtIsNull(user, hash)
                .orElseThrow(() -> new CustomException(ErrorCode.AUTH_INVALID_TOKEN));

        if (!session.isValid()) {
            throw new CustomException(ErrorCode.AUTH_INVALID_TOKEN);
        }

        // 새 액세스 토큰 발급
        String newAccessToken = jwtProvider.generateAccessToken(userId);

        return new AuthResponseDto.Refresh(newAccessToken);
    }

    // 리프레시 토큰 세션 저장
    private void saveSession(User user, String refreshToken) {
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
    }
}
package com.semojum.backend.domain.auth.service;

import com.semojum.backend.domain.auth.dto.AuthRequestDto;
import com.semojum.backend.domain.auth.dto.AuthResponseDto;
import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.entity.UserSession;
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

// V3: 자체 가입·소셜 로그인 제거. 운영자가 발급한 계정(loginId/PW)으로만 로그인한다.
@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final JwtProvider jwtProvider;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public AuthResponseDto.Login login(AuthRequestDto.Login request) {
        // 발급 ID로 유저 조회
        User user = userRepository.findByLoginId(request.loginId())
                .orElseThrow(() -> {
                    log.warn("로그인 실패: loginId={} (존재하지 않는 ID)", request.loginId());
                    return new CustomException(ErrorCode.AUTH_INVALID_CREDENTIALS);
                });

        // 비밀번호 검증
        if (user.getPassword() == null
                || !passwordEncoder.matches(request.password(), user.getPassword())) {
            log.warn("로그인 실패: loginId={} (비밀번호 불일치)", request.loginId());
            throw new CustomException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        // 비활성 계정 차단 (계약 만료·퇴사 등 운영자 조치)
        if (!user.isActive()) {
            log.warn("로그인 거부: loginId={} (비활성 계정)", request.loginId());
            throw new CustomException(ErrorCode.AUTH_INACTIVE_ACCOUNT);
        }

        // 중복 로그인 금지: 기존 활성 세션 전부 revoke (신규 로그인이 기존을 밀어냄)
        userSessionRepository.revokeAllActiveByUser(user, LocalDateTime.now());

        // JWT 발급 (subject: UUID)
        String accessToken = jwtProvider.generateAccessToken(user.getId().toString());
        String refreshToken = jwtProvider.generateRefreshToken(user.getId().toString());

        // 리프레시 토큰 세션 저장 (계정당 활성 1개)
        saveSession(user, refreshToken);

        log.info("로그인 성공: loginId={}", request.loginId());
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

        // 세션 유효성 확인 (다른 곳에서 로그인해 revoke됐다면 여기서 차단됨)
        String hash = jwtProvider.hashToken(refreshToken);
        UserSession session = userSessionRepository
                .findByUserAndRefreshTokenHashAndRevokedAtIsNull(user, hash)
                .orElseThrow(() -> new CustomException(ErrorCode.AUTH_INVALID_TOKEN));

        if (!session.isValid()) {
            throw new CustomException(ErrorCode.AUTH_INVALID_TOKEN);
        }

        // 비활성 계정은 재발급도 차단 (비활성화 시 세션도 revoke되지만 이중 방어)
        if (!user.isActive()) {
            log.warn("토큰 재발급 거부: loginId={} (비활성 계정)", user.getLoginId());
            throw new CustomException(ErrorCode.AUTH_INACTIVE_ACCOUNT);
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

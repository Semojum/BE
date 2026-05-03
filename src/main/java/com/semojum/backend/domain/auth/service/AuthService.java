package com.semojum.backend.domain.auth.service;

import com.semojum.backend.domain.auth.dto.AuthRequestDto;
import com.semojum.backend.domain.auth.dto.AuthResponseDto;
import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public AuthResponseDto.SignUp signUp(AuthRequestDto.SignUp request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new CustomException(ErrorCode.AUTH_DUPLICATE_EMAIL);
        }

        User user = User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .name(request.name())
                .build();

        userRepository.save(user);

        return new AuthResponseDto.SignUp(user.getEmail(), user.getName());
    }

    @Transactional(readOnly = true)
    public AuthResponseDto.Login login(AuthRequestDto.Login request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new CustomException(ErrorCode.AUTH_INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new CustomException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        String accessToken = jwtProvider.generateAccessToken(user.getEmail());
        String refreshToken = jwtProvider.generateRefreshToken(user.getEmail());

        return new AuthResponseDto.Login(accessToken, refreshToken);
    }
}
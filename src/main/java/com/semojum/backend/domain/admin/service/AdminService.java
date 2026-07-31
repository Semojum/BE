package com.semojum.backend.domain.admin.service;

import com.semojum.backend.domain.admin.dto.AdminRequestDto;
import com.semojum.backend.domain.admin.dto.AdminResponseDto;
import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.enums.AuthProvider;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.org.entity.Organization;
import com.semojum.backend.domain.org.repository.OrganizationRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.UUID;

// V3 운영자 기능: 기관 생성·계정 발급·비밀번호 재발급 (관리자 페이지는 2차 — 최소 API로 제공)
@Service
@RequiredArgsConstructor
public class AdminService {

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    // 헷갈리는 문자(0/O, 1/l/I) 제외한 난수 비밀번호 문자셋
    private static final String PW_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final int PW_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Transactional
    public AdminResponseDto.Org createOrganization(AdminRequestDto.CreateOrg request) {
        Organization org = Organization.builder()
                .name(request.name())
                .contractExpiresAt(request.contractExpiresAt())
                .build();
        organizationRepository.save(org);
        return new AdminResponseDto.Org(org.getId().toString(), org.getName());
    }

    // 계정 발급: 초기 비밀번호 난수 생성 → 응답으로 1회만 노출, 사용자 변경 불가
    @Transactional
    public AdminResponseDto.IssuedAccount issueAccount(AdminRequestDto.IssueAccount request) {
        Organization org = organizationRepository.findById(UUID.fromString(request.organizationId()))
                .orElseThrow(() -> new CustomException(ErrorCode.ORG_NOT_FOUND));

        if (userRepository.existsByLoginId(request.loginId())) {
            throw new CustomException(ErrorCode.AUTH_DUPLICATE_LOGIN_ID);
        }

        String rawPassword = generatePassword();
        User user = User.builder()
                .loginId(request.loginId())
                .organization(org)
                .name(request.name())
                .password(passwordEncoder.encode(rawPassword))
                .provider(AuthProvider.EMAIL)
                .build();
        userRepository.save(user);

        return new AdminResponseDto.IssuedAccount(request.loginId(), rawPassword);
    }

    // 비밀번호 재발급 (분실·유출 대응 — 계정·작업물은 유지, PW만 교체)
    @Transactional
    public AdminResponseDto.IssuedAccount reissuePassword(String loginId) {
        User user = userRepository.findByLoginId(loginId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        String rawPassword = generatePassword();
        user.reissuePassword(passwordEncoder.encode(rawPassword));

        return new AdminResponseDto.IssuedAccount(loginId, rawPassword);
    }

    private String generatePassword() {
        StringBuilder sb = new StringBuilder(PW_LENGTH);
        for (int i = 0; i < PW_LENGTH; i++) {
            sb.append(PW_CHARS.charAt(RANDOM.nextInt(PW_CHARS.length())));
        }
        return sb.toString();
    }
}

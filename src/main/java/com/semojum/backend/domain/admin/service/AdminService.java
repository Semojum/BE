package com.semojum.backend.domain.admin.service;

import com.semojum.backend.domain.admin.dto.AdminRequestDto;
import com.semojum.backend.domain.admin.dto.AdminResponseDto;
import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.enums.Role;
import com.semojum.backend.domain.auth.enums.UserStatus;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.auth.repository.UserSessionRepository;
import com.semojum.backend.domain.org.entity.Organization;
import com.semojum.backend.domain.org.repository.OrganizationRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// V3 운영자 기능: 기관 생성·계정 발급·비밀번호 재발급 (관리자 페이지는 2차 — 최소 API로 제공)
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminService {

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final PasswordEncoder passwordEncoder;

    // 초기 비밀번호 = 영어 소문자+숫자 혼합 난수 6자리 (유저 확정 2026-08-20 — 전달·입력 편의, 12자에서 축소)
    // 헷갈리는 문자(0/o, 1/l) 제외 관례 유지. 계정은 운영자 발급 전용 + 중복 로그인 금지라 노출면이 작다
    private static final String PW_CHARS = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final int PW_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    // 생성 시 계약 유형 지정 가능 (2026-08-20) — 미지정 시 FREE(과금 실수 방지 기본값 유지)
    private static final java.util.Set<String> CONTRACT_TYPES =
            java.util.Set.of("BASIC", "STANDARD", "PREMIUM", "FREE", "COUPON");

    @Transactional
    public AdminResponseDto.Org createOrganization(AdminRequestDto.CreateOrg request) {
        String code = request.code() != null ? request.code() : nextAutoCode();
        if (organizationRepository.existsByCode(code)) {
            throw new CustomException(ErrorCode.ORG_CODE_DUPLICATE);
        }
        if (request.contractType() != null && !CONTRACT_TYPES.contains(request.contractType())) {
            log.warn("계약 유형 오류: {}", request.contractType());
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        Organization org = Organization.builder()
                .name(request.name())
                .code(code)
                .contractExpiresAt(request.contractExpiresAt())
                .contractType(request.contractType())
                .build();
        organizationRepository.save(org);
        return new AdminResponseDto.Org(org.getId().toString(), org.getName(), org.getCode(), org.getContractType());
    }

    // 계정 일괄 발급: loginId = {기관코드}{순번 2자리, 99 초과 시 자릿수 증가} (예: kblib01 … kblib99, kblib100)
    // 초기 비밀번호는 난수 생성 → 응답으로 1회만 노출, 사용자 변경 불가
    @Transactional
    public AdminResponseDto.IssuedAccounts issueAccounts(AdminRequestDto.IssueAccounts request) {
        Organization org = organizationRepository.findById(UUID.fromString(request.organizationId()))
                .filter(o -> o.getDeletedAt() == null)   // 삭제된 기관에는 계정 발급 불가
                .orElseThrow(() -> new CustomException(ErrorCode.ORG_NOT_FOUND));

        int next = nextSequence(org.getCode());
        List<AdminResponseDto.IssuedAccount> accounts = new ArrayList<>();
        for (int i = 0; i < request.count(); i++) {
            String loginId = org.getCode() + String.format("%02d", next + i);
            String rawPassword = generatePassword();
            userRepository.save(User.builder()
                    .loginId(loginId)
                    .organization(org)
                    .password(passwordEncoder.encode(rawPassword))
                    .build());
            accounts.add(new AdminResponseDto.IssuedAccount(loginId, rawPassword));
        }
        return new AdminResponseDto.IssuedAccounts(accounts);
    }

    // 기관 코드 뒤 숫자 접미사의 최댓값 + 1. 다른 기관 코드가 이 코드로 시작해도(kb vs kblib)
    // 숫자 외 문자가 끼면 걸러지므로 안전하다.
    private int nextSequence(String code) {
        Pattern p = Pattern.compile("^" + Pattern.quote(code) + "(\\d+)$");
        return userRepository.findLoginIdsByPrefix(code).stream()
                .map(p::matcher)
                .filter(Matcher::matches)
                .mapToInt(m -> Integer.parseInt(m.group(1)))
                .max().orElse(0) + 1;
    }

    // 코드 미입력 시 orgNN 자동 부여 (기존 자동 코드의 최대 번호 + 1)
    private String nextAutoCode() {
        Pattern p = Pattern.compile("^org(\\d+)$");
        int next = organizationRepository.findAllCodes().stream()
                .map(p::matcher)
                .filter(Matcher::matches)
                .mapToInt(m -> Integer.parseInt(m.group(1)))
                .max().orElse(0) + 1;
        return String.format("org%02d", next);
    }

    // 계정 상태 변경 — INACTIVE 시 활성 세션 전부 revoke (액세스 토큰은 JWT stateless라 최대 1시간 유효)
    @Transactional
    public AdminResponseDto.AccountStatus updateStatus(String loginId, AdminRequestDto.UpdateStatus request) {
        User user = userRepository.findByLoginId(loginId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        user.changeStatus(request.status());
        if (request.status() == UserStatus.INACTIVE) {
            userSessionRepository.revokeAllActiveByUser(user, LocalDateTime.now());
        }
        return new AdminResponseDto.AccountStatus(loginId, user.getStatus().name());
    }

    // 계정 역할 변경 — ROLE_ADMIN은 운영·테스트용 계정 분류
    @Transactional
    public AdminResponseDto.AccountRole updateRole(String loginId, AdminRequestDto.UpdateRole request) {
        User user = userRepository.findByLoginId(loginId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        user.changeRole(request.role());
        return new AdminResponseDto.AccountRole(loginId, user.getRole().name());
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

package com.semojum.backend.domain.admin.service;

import com.semojum.backend.domain.admin.dto.AdminRequestDto;
import com.semojum.backend.domain.admin.dto.AdminResponseDto;
import com.semojum.backend.domain.auth.entity.User;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        String code = request.code() != null ? request.code() : nextAutoCode();
        if (organizationRepository.existsByCode(code)) {
            throw new CustomException(ErrorCode.ORG_CODE_DUPLICATE);
        }
        Organization org = Organization.builder()
                .name(request.name())
                .code(code)
                .contractExpiresAt(request.contractExpiresAt())
                .build();
        organizationRepository.save(org);
        return new AdminResponseDto.Org(org.getId().toString(), org.getName(), org.getCode());
    }

    // 계정 일괄 발급: loginId = {기관코드}{순번 2자리, 99 초과 시 자릿수 증가} (예: kblib01 … kblib99, kblib100)
    // 초기 비밀번호는 난수 생성 → 응답으로 1회만 노출, 사용자 변경 불가
    @Transactional
    public AdminResponseDto.IssuedAccounts issueAccounts(AdminRequestDto.IssueAccounts request) {
        Organization org = organizationRepository.findById(UUID.fromString(request.organizationId()))
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

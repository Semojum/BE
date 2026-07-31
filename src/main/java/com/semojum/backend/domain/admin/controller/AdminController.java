package com.semojum.backend.domain.admin.controller;

import com.semojum.backend.domain.admin.dto.AdminRequestDto;
import com.semojum.backend.domain.admin.dto.AdminResponseDto;
import com.semojum.backend.domain.admin.service.AdminService;
import com.semojum.backend.global.exception.ApiResponse;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

// V3 운영자 API — X-Admin-Key 헤더로 보호 (관리자 페이지는 2차, 그 전까지의 최소 운영 수단)
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @Value("${admin.api-key:}")
    private String adminApiKey;

    @PostMapping("/orgs")
    public ApiResponse<AdminResponseDto.Org> createOrg(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @RequestBody @Valid AdminRequestDto.CreateOrg request
    ) {
        validateAdminKey(adminKey);
        return ApiResponse.success(adminService.createOrganization(request));
    }

    @PostMapping("/accounts")
    public ApiResponse<AdminResponseDto.IssuedAccount> issueAccount(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @RequestBody @Valid AdminRequestDto.IssueAccount request
    ) {
        validateAdminKey(adminKey);
        return ApiResponse.success(adminService.issueAccount(request));
    }

    @PostMapping("/accounts/{loginId}/password-reissue")
    public ApiResponse<AdminResponseDto.IssuedAccount> reissuePassword(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @PathVariable String loginId
    ) {
        validateAdminKey(adminKey);
        return ApiResponse.success(adminService.reissuePassword(loginId));
    }

    // 키 미설정(빈 값) 상태에서는 전부 차단. 비교는 타이밍 공격 방지를 위해 constant-time
    private void validateAdminKey(String provided) {
        if (adminApiKey == null || adminApiKey.isBlank() || provided == null
                || !MessageDigest.isEqual(
                        adminApiKey.getBytes(StandardCharsets.UTF_8),
                        provided.getBytes(StandardCharsets.UTF_8))) {
            throw new CustomException(ErrorCode.COMMON_FORBIDDEN);
        }
    }
}

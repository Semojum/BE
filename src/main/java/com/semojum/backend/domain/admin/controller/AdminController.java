package com.semojum.backend.domain.admin.controller;

import com.semojum.backend.domain.admin.dto.AdminRequestDto;
import com.semojum.backend.domain.admin.dto.AdminResponseDto;
import com.semojum.backend.domain.admin.service.AdminService;
import com.semojum.backend.domain.billing.dto.PricingDto;
import com.semojum.backend.domain.billing.service.PricingAdminService;
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
    private final PricingAdminService pricingAdminService;

    @Value("${admin.api-key:}")
    private String adminApiKey;

    // 단가·배율 관리 변수 — 현재(최신) 판 조회
    @GetMapping("/pricing")
    public ApiResponse<PricingDto.Response> getPricing(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey
    ) {
        validateAdminKey(adminKey);
        return ApiResponse.success(pricingAdminService.getCurrent());
    }

    // 단가·배율 관리 변수 — 새 판 등록 (config 전문 교체, 과거 판은 이력으로 보존)
    @PutMapping("/pricing")
    public ApiResponse<PricingDto.Response> updatePricing(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @RequestBody @Valid PricingDto.Update request
    ) {
        validateAdminKey(adminKey);
        return ApiResponse.success(pricingAdminService.update(request));
    }

    @PostMapping("/orgs")
    public ApiResponse<AdminResponseDto.Org> createOrg(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @RequestBody @Valid AdminRequestDto.CreateOrg request
    ) {
        validateAdminKey(adminKey);
        return ApiResponse.success(adminService.createOrganization(request));
    }

    @PostMapping("/accounts")
    public ApiResponse<AdminResponseDto.IssuedAccounts> issueAccounts(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @RequestBody @Valid AdminRequestDto.IssueAccounts request
    ) {
        validateAdminKey(adminKey);
        return ApiResponse.success(adminService.issueAccounts(request));
    }

    @PatchMapping("/accounts/{loginId}/status")
    public ApiResponse<AdminResponseDto.AccountStatus> updateAccountStatus(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @PathVariable String loginId,
            @RequestBody @Valid AdminRequestDto.UpdateStatus request
    ) {
        validateAdminKey(adminKey);
        return ApiResponse.success(adminService.updateStatus(loginId, request));
    }

    @PatchMapping("/accounts/{loginId}/role")
    public ApiResponse<AdminResponseDto.AccountRole> updateAccountRole(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @PathVariable String loginId,
            @RequestBody @Valid AdminRequestDto.UpdateRole request
    ) {
        validateAdminKey(adminKey);
        return ApiResponse.success(adminService.updateRole(loginId, request));
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

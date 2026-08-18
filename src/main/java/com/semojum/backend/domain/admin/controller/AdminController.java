package com.semojum.backend.domain.admin.controller;

import com.semojum.backend.domain.admin.dto.AdminRequestDto;
import com.semojum.backend.domain.admin.dto.AdminResponseDto;
import com.semojum.backend.domain.admin.service.AdminService;
import com.semojum.backend.domain.billing.dto.PricingDto;
import com.semojum.backend.domain.billing.service.PricingAdminService;
import com.semojum.backend.domain.admin.dto.AdminMonitorDto;
import com.semojum.backend.domain.admin.dto.AdminStatsDto;
import com.semojum.backend.domain.admin.service.AdminMonitorService;
import com.semojum.backend.domain.admin.service.AdminStatsService;
import com.semojum.backend.domain.support.dto.SupportDto;
import com.semojum.backend.domain.support.service.AdminSupportService;
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
    private final AdminSupportService adminSupportService;
    private final AdminMonitorService adminMonitorService;
    private final AdminStatsService adminStatsService;

    // ── 통계 (T1-1 대표 · T1-2 상세) ──
    @GetMapping("/stats/overview")
    public ApiResponse<AdminStatsDto.Overview> getStatsOverview(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @RequestParam(required = false) String period
    ) {
        validateAdminKey(adminKey);
        return ApiResponse.success(adminStatsService.getOverview(period));
    }

    @GetMapping("/stats/workload")
    public ApiResponse<AdminStatsDto.Workload> getStatsWorkload(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @RequestParam(required = false) String unit
    ) {
        validateAdminKey(adminKey);
        return ApiResponse.success(adminStatsService.getWorkload(unit));
    }

    @GetMapping("/stats/layout-cost")
    public ApiResponse<AdminStatsDto.LayoutCost> getStatsLayoutCost(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @RequestParam(required = false) String month
    ) {
        validateAdminKey(adminKey);
        return ApiResponse.success(adminStatsService.getLayoutCost(month));
    }

    // ── 실시간 모니터링 (T1-3) — 전 기관 최근 작업, 10초 폴링 ──
    @GetMapping("/jobs")
    public ApiResponse<AdminMonitorDto.Jobs> listMonitorJobs(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer hours,
            @RequestParam(required = false) Integer size
    ) {
        validateAdminKey(adminKey);
        return ApiResponse.success(adminMonitorService.listJobs(status, hours, size));
    }

    // ── 작업 상세 (T1-4) — 요청 정보(접속 메타데이터)·처리 비용·쪽별 결과 ──
    @GetMapping("/jobs/{jobId}")
    public ApiResponse<AdminMonitorDto.JobDetail> getMonitorJobDetail(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @PathVariable String jobId
    ) {
        validateAdminKey(adminKey);
        return ApiResponse.success(adminMonitorService.getJobDetail(jobId));
    }

    @Value("${admin.api-key:}")
    private String adminApiKey;

    // ── 공지 (T1-10) ──
    @PostMapping("/notices")
    public ApiResponse<SupportDto.NoticeItem> createNotice(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @RequestBody @Valid SupportDto.CreateNotice request
    ) {
        validateAdminKey(adminKey);
        return ApiResponse.success(adminSupportService.createNotice(request));
    }

    @GetMapping("/notices")
    public ApiResponse<java.util.List<SupportDto.NoticeItem>> listNotices(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey
    ) {
        validateAdminKey(adminKey);
        return ApiResponse.success(adminSupportService.listNotices());
    }

    // ── 문의 (T1-9) ──
    @GetMapping("/inquiries")
    public ApiResponse<java.util.List<SupportDto.InquiryItem>> listInquiries(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type
    ) {
        validateAdminKey(adminKey);
        return ApiResponse.success(adminSupportService.listInquiries(status, type));
    }

    @PatchMapping("/inquiries/{inquiryId}/status")
    public ApiResponse<SupportDto.InquiryItem> updateInquiryStatus(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @PathVariable java.util.UUID inquiryId,
            @RequestBody @Valid SupportDto.UpdateInquiryStatus request
    ) {
        validateAdminKey(adminKey);
        return ApiResponse.success(adminSupportService.updateInquiryStatus(inquiryId, request.status()));
    }

    // ── 주문·수납 (T1-7) ──
    @PostMapping("/orders")
    public ApiResponse<SupportDto.OrderItem> createOrder(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @RequestBody @Valid SupportDto.CreateOrder request
    ) {
        validateAdminKey(adminKey);
        return ApiResponse.success(adminSupportService.createOrder(request));
    }

    @PatchMapping("/orders/{orderId}")
    public ApiResponse<SupportDto.OrderItem> updateOrder(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @PathVariable java.util.UUID orderId,
            @RequestBody @Valid SupportDto.UpdateOrder request
    ) {
        validateAdminKey(adminKey);
        return ApiResponse.success(adminSupportService.updateOrder(orderId, request));
    }

    @GetMapping("/orders")
    public ApiResponse<java.util.List<SupportDto.OrderItem>> listOrders(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @RequestParam(required = false) java.util.UUID organizationId
    ) {
        validateAdminKey(adminKey);
        return ApiResponse.success(adminSupportService.listOrders(organizationId));
    }

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

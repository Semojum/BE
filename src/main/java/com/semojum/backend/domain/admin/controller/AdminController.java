package com.semojum.backend.domain.admin.controller;

import com.semojum.backend.domain.admin.dto.AdminRequestDto;
import com.semojum.backend.domain.admin.dto.AdminResponseDto;
import com.semojum.backend.domain.admin.service.AdminService;
import com.semojum.backend.domain.billing.dto.PricingDto;
import com.semojum.backend.domain.billing.service.PricingAdminService;
import com.semojum.backend.domain.admin.dto.AdminMonitorDto;
import com.semojum.backend.domain.admin.dto.AdminOrgDto;
import com.semojum.backend.domain.admin.service.AdminOrgManageService;
import com.semojum.backend.domain.admin.dto.AdminStatsDto;
import com.semojum.backend.domain.admin.service.AdminCopyService;
import com.semojum.backend.domain.admin.service.AdminMonitorService;
import com.semojum.backend.domain.admin.service.AdminStatsService;
import com.semojum.backend.domain.support.dto.SupportDto;
import com.semojum.backend.domain.support.service.AdminSupportService;
import com.semojum.backend.global.exception.ApiResponse;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;


// V3 운영자 API — JWT(ROLE_ADMIN) 전용. 운영자 콘솔(admin.semo-jum.com)이 로그인 후 Bearer로 호출
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final PricingAdminService pricingAdminService;
    private final AdminSupportService adminSupportService;
    private final AdminMonitorService adminMonitorService;
    private final AdminStatsService adminStatsService;
    private final AdminOrgManageService adminOrgManageService;
    private final AdminCopyService adminCopyService;
    private final com.semojum.backend.domain.user.service.UserService userService;

    // ── 기관·계정 통합 표 (T1-6) — 기관별 계정 + 소계(월 사용량·관리자 마지막 로그인) ──
    @GetMapping("/orgs")
    public ApiResponse<AdminOrgDto.Orgs> listOrgs(
            @RequestParam(required = false) String month
    ) {
        validateAdminRole();
        return ApiResponse.success(adminOrgManageService.listOrgs(month));
    }

    // ── 기관 정보 (T1-7) ──
    @GetMapping("/orgs/{orgId}")
    public ApiResponse<AdminOrgDto.OrgDetail> getOrg(
            @PathVariable java.util.UUID orgId
    ) {
        validateAdminRole();
        return ApiResponse.success(adminOrgManageService.getOrg(orgId));
    }

    // 이름·계약(구분/기간)·할당 크레딧 수정 — T2 크레딧 추가 요청 처리 = creditAllocated 상향
    @PatchMapping("/orgs/{orgId}")
    public ApiResponse<AdminOrgDto.OrgDetail> updateOrg(
            @PathVariable java.util.UUID orgId,
            @RequestBody @Valid AdminOrgDto.UpdateOrg request
    ) {
        validateAdminRole();
        return ApiResponse.success(adminOrgManageService.updateOrg(orgId, request));
    }

    // 쿠폰 발급·목록 (T1-7) — 차감은 쿠폰 잔량부터, 소진되면 계약 크레딧
    @PostMapping("/orgs/{orgId}/coupons")
    public ApiResponse<AdminOrgDto.CouponItem> issueCoupon(
            @PathVariable java.util.UUID orgId,
            @RequestBody @Valid AdminOrgDto.CreateCoupon request
    ) {
        validateAdminRole();
        return ApiResponse.success(adminOrgManageService.issueCoupon(orgId, request));
    }

    @GetMapping("/orgs/{orgId}/coupons")
    public ApiResponse<java.util.List<AdminOrgDto.CouponItem>> listCoupons(
            @PathVariable java.util.UUID orgId
    ) {
        validateAdminRole();
        return ApiResponse.success(adminOrgManageService.listCoupons(orgId));
    }

    // 기관 삭제(소프트) — 소속 계정 전부 잠금. 실삭제는 보관 기간 정책 확정 후
    @DeleteMapping("/orgs/{orgId}")
    public ApiResponse<AdminOrgDto.DeleteOrgResult> deleteOrg(
            @PathVariable java.util.UUID orgId
    ) {
        validateAdminRole();
        return ApiResponse.success(adminOrgManageService.deleteOrg(orgId));
    }

    // 계정 삭제(소프트) — 잠금 + 삭제 표식, 작업물 보관
    @DeleteMapping("/accounts/{loginId}")
    public ApiResponse<AdminOrgDto.DeleteAccountResult> deleteAccount(
            @PathVariable String loginId
    ) {
        validateAdminRole();
        return ApiResponse.success(adminOrgManageService.deleteAccount(loginId));
    }

    // ── 통계 (T1-1 대표 · T1-2 상세) ──
    @GetMapping("/stats/overview")
    public ApiResponse<AdminStatsDto.Overview> getStatsOverview(
            @RequestParam(required = false) String period
    ) {
        validateAdminRole();
        return ApiResponse.success(adminStatsService.getOverview(period));
    }

    @GetMapping("/stats/workload")
    public ApiResponse<AdminStatsDto.Workload> getStatsWorkload(
            @RequestParam(required = false) String unit
    ) {
        validateAdminRole();
        return ApiResponse.success(adminStatsService.getWorkload(unit));
    }

    // 기관별 수익성 — 차액 = 환산 매출(차감 크레딧 × creditPriceKrw) − 원가. 단가는 관리 변수
    @GetMapping("/stats/profitability")
    public ApiResponse<AdminStatsDto.Profitability> getStatsProfitability(
            @RequestParam(required = false) String month
    ) {
        validateAdminRole();
        return ApiResponse.success(adminStatsService.getProfitability(month));
    }

    @GetMapping("/stats/layout-cost")
    public ApiResponse<AdminStatsDto.LayoutCost> getStatsLayoutCost(
            @RequestParam(required = false) String month
    ) {
        validateAdminRole();
        return ApiResponse.success(adminStatsService.getLayoutCost(month));
    }

    // ── 실시간 모니터링 (T1-3) — 전 기관 최근 작업, 10초 폴링 ──
    @GetMapping("/jobs")
    public ApiResponse<AdminMonitorDto.Jobs> listMonitorJobs(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer hours,
            @RequestParam(required = false) Integer size
    ) {
        validateAdminRole();
        return ApiResponse.success(adminMonitorService.listJobs(status, hours, size));
    }

    // ── 결과 미리보기 (T1-5) — 페이지 결과+원본(presigned). 확인용 — 편집·재변환 없음 ──
    @GetMapping("/jobs/{jobId}/pages/{pageNo}")
    public ApiResponse<com.semojum.backend.domain.job.dto.JobResponseDto.JobDetail> getMonitorJobPage(
            @PathVariable String jobId,
            @PathVariable int pageNo
    ) {
        validateAdminRole();
        return ApiResponse.success(userService.getJobPageAsAdmin(jobId, pageNo));
    }

    // ── 마이페이지로 보내기 (T1-5) — 사본을 운영자 계정으로. targetLoginId 없으면 JWT 본인 ──
    @PostMapping("/jobs/{jobId}/send-to-mypage")
    public ApiResponse<java.util.Map<String, Object>> sendToMypage(
            @PathVariable String jobId,
            @RequestBody(required = false) java.util.Map<String, String> body,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.springframework.security.core.userdetails.UserDetails userDetails
    ) {
        validateAdminRole();
        String targetLoginId = body == null ? null : body.get("targetLoginId");
        return ApiResponse.success(adminCopyService.copyToMypage(jobId, targetLoginId,
                userDetails == null ? null : userDetails.getUsername()));
    }

    // ── 작업 상세 (T1-4) — 요청 정보(접속 메타데이터)·처리 비용·쪽별 결과 ──
    @GetMapping("/jobs/{jobId}")
    public ApiResponse<AdminMonitorDto.JobDetail> getMonitorJobDetail(
            @PathVariable String jobId
    ) {
        validateAdminRole();
        return ApiResponse.success(adminMonitorService.getJobDetail(jobId));
    }

    // ── 공지 (T1-10) ──
    @PostMapping("/notices")
    public ApiResponse<SupportDto.NoticeItem> createNotice(
            @RequestBody @Valid SupportDto.CreateNotice request
    ) {
        validateAdminRole();
        return ApiResponse.success(adminSupportService.createNotice(request));
    }

    @GetMapping("/notices")
    public ApiResponse<java.util.List<SupportDto.NoticeItem>> listNotices() {
        validateAdminRole();
        return ApiResponse.success(adminSupportService.listNotices());
    }

    // ── 문의 (T1-9) ──
    @GetMapping("/inquiries")
    public ApiResponse<java.util.List<SupportDto.InquiryItem>> listInquiries(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type
    ) {
        validateAdminRole();
        return ApiResponse.success(adminSupportService.listInquiries(status, type));
    }

    @PatchMapping("/inquiries/{inquiryId}/status")
    public ApiResponse<SupportDto.InquiryItem> updateInquiryStatus(
            @PathVariable java.util.UUID inquiryId,
            @RequestBody @Valid SupportDto.UpdateInquiryStatus request
    ) {
        validateAdminRole();
        return ApiResponse.success(adminSupportService.updateInquiryStatus(inquiryId, request.status()));
    }

    // ── 주문·수납 (T1-7) ──
    @PostMapping("/orders")
    public ApiResponse<SupportDto.OrderItem> createOrder(
            @RequestBody @Valid SupportDto.CreateOrder request
    ) {
        validateAdminRole();
        return ApiResponse.success(adminSupportService.createOrder(request));
    }

    @PatchMapping("/orders/{orderId}")
    public ApiResponse<SupportDto.OrderItem> updateOrder(
            @PathVariable java.util.UUID orderId,
            @RequestBody @Valid SupportDto.UpdateOrder request
    ) {
        validateAdminRole();
        return ApiResponse.success(adminSupportService.updateOrder(orderId, request));
    }

    @GetMapping("/orders")
    public ApiResponse<java.util.List<SupportDto.OrderItem>> listOrders(
            @RequestParam(required = false) java.util.UUID organizationId
    ) {
        validateAdminRole();
        return ApiResponse.success(adminSupportService.listOrders(organizationId));
    }

    // 단가·배율 관리 변수 — 현재(최신) 판 조회
    @GetMapping("/pricing")
    public ApiResponse<PricingDto.Response> getPricing() {
        validateAdminRole();
        return ApiResponse.success(pricingAdminService.getCurrent());
    }

    // 단가·배율 관리 변수 — 새 판 등록 (config 전문 교체, 과거 판은 이력으로 보존)
    @PutMapping("/pricing")
    public ApiResponse<PricingDto.Response> updatePricing(
            @RequestBody @Valid PricingDto.Update request
    ) {
        validateAdminRole();
        return ApiResponse.success(pricingAdminService.update(request));
    }

    @PostMapping("/orgs")
    public ApiResponse<AdminResponseDto.Org> createOrg(
            @RequestBody @Valid AdminRequestDto.CreateOrg request
    ) {
        validateAdminRole();
        return ApiResponse.success(adminService.createOrganization(request));
    }

    @PostMapping("/accounts")
    public ApiResponse<AdminResponseDto.IssuedAccounts> issueAccounts(
            @RequestBody @Valid AdminRequestDto.IssueAccounts request
    ) {
        validateAdminRole();
        return ApiResponse.success(adminService.issueAccounts(request));
    }

    @PatchMapping("/accounts/{loginId}/status")
    public ApiResponse<AdminResponseDto.AccountStatus> updateAccountStatus(
            @PathVariable String loginId,
            @RequestBody @Valid AdminRequestDto.UpdateStatus request
    ) {
        validateAdminRole();
        return ApiResponse.success(adminService.updateStatus(loginId, request));
    }

    @PatchMapping("/accounts/{loginId}/role")
    public ApiResponse<AdminResponseDto.AccountRole> updateAccountRole(
            @PathVariable String loginId,
            @RequestBody @Valid AdminRequestDto.UpdateRole request
    ) {
        validateAdminRole();
        return ApiResponse.success(adminService.updateRole(loginId, request));
    }

    @PostMapping("/accounts/{loginId}/password-reissue")
    public ApiResponse<AdminResponseDto.IssuedAccount> reissuePassword(
            @PathVariable String loginId
    ) {
        validateAdminRole();
        return ApiResponse.success(adminService.reissuePassword(loginId));
    }

    // 운영자 검증 — JWT(ROLE_ADMIN) 전용 (X-Admin-Key는 2026-08-19 폐기, 감사는 액세스 로그의 user= 필드가 담당).
    // SecurityConfig의 hasRole과 이중 방어
    private void validateAdminRole() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().stream()
                .noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            throw new CustomException(ErrorCode.COMMON_FORBIDDEN);
        }
    }
}

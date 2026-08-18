package com.semojum.backend.domain.support.controller;

import com.semojum.backend.domain.support.dto.SupportDto;
import com.semojum.backend.domain.support.service.OrgSupportService;
import com.semojum.backend.global.exception.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** T2 기관 관리자 — 공지·주문 조회 + 요청(크레딧 추가·계정 발급) 접수 (ROLE_ORG_ADMIN 전용) */
@RestController
@RequestMapping("/api/org")
@RequiredArgsConstructor
public class OrgSupportController {

    private final OrgSupportService orgSupportService;

    /** 받은 공지 — 노출 기간 내의 전체 공지 + 우리 기관 공지 (기간 지나면 자동 종료) */
    @GetMapping("/notices")
    public ApiResponse<List<SupportDto.OrgNotice>> getNotices(@AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(orgSupportService.getNotices(userDetails.getUsername()));
    }

    /** 주문 내역 — 결제·계산서 상태 + 증빙 받는 사람 */
    @GetMapping("/orders")
    public ApiResponse<SupportDto.OrgOrders> getOrders(@AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(orgSupportService.getOrders(userDetails.getUsername()));
    }

    /** 증빙(계산서) 받는 사람 변경 — null·빈 문자열 = 제거 */
    @PatchMapping("/receipt-email")
    public ApiResponse<Void> updateReceiptEmail(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid SupportDto.UpdateReceiptEmail request
    ) {
        orgSupportService.updateReceiptEmail(userDetails.getUsername(), request.email());
        return ApiResponse.success(null);
    }

    /** 크레딧 추가·계정 발급 요청 — 문의 목록(T1-9)으로 접수되고 처리 상태가 이 화면에 남는다 */
    @PostMapping("/requests")
    public ApiResponse<SupportDto.RequestItem> createRequest(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid SupportDto.CreateRequest request
    ) {
        return ApiResponse.success(orgSupportService.createRequest(userDetails.getUsername(), request));
    }

    /** 우리 기관의 요청·문의 목록 + 처리 상태 */
    @GetMapping("/requests")
    public ApiResponse<List<SupportDto.RequestItem>> getRequests(@AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(orgSupportService.getRequests(userDetails.getUsername()));
    }

    /** 요청 취소 — OPEN(미답변) 상태의 우리 기관 요청만 */
    @DeleteMapping("/requests/{requestId}")
    public ApiResponse<Void> cancelRequest(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable UUID requestId
    ) {
        orgSupportService.cancelRequest(userDetails.getUsername(), requestId);
        return ApiResponse.success(null);
    }
}

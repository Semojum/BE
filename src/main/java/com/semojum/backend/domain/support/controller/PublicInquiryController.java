package com.semojum.backend.domain.support.controller;

import com.semojum.backend.domain.support.service.PublicInquiryService;
import com.semojum.backend.global.exception.ApiResponse;
import com.semojum.backend.global.util.ClientInfoResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 홈페이지 문의 접수 (무인증 공개) — 도입 문의·오류 신고가 T1-9 문의 목록으로 유입 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicInquiryController {

    private final PublicInquiryService publicInquiryService;
    private final ClientInfoResolver clientInfoResolver;

    public record SubmitRequest(
            @NotBlank String type,                              // ONBOARDING(도입 문의) | ERROR_REPORT | ETC
            @Size(max = 50) String name,                        // 보낸 사람 이름 (선택)
            @NotBlank @Email @Size(max = 100) String email,
            @NotBlank @Size(max = 2000) String message,
            String website                                      // 허니팟 — 화면에서 숨김, 채워지면 봇
    ) {}

    @PostMapping("/inquiries")
    public ApiResponse<Void> submit(@RequestBody @Valid SubmitRequest request,
                                    HttpServletRequest httpRequest) {
        publicInquiryService.submit(request.type(), request.name(), request.email(),
                request.message(), request.website(),
                clientInfoResolver.resolve(httpRequest).ip());
        return ApiResponse.success(null);
    }
}

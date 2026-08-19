package com.semojum.backend.domain.support.controller;

import com.semojum.backend.domain.support.dto.SupportDto;
import com.semojum.backend.domain.support.service.OrgSupportService;
import com.semojum.backend.global.exception.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 로그인 전 공지 조회 (무인증 공개) — 전체 대상 공지만, 노출 기간 내. 기관별 공지는 로그인 후 T2 공지가 담당 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicNoticeController {

    private final OrgSupportService orgSupportService;

    @GetMapping("/notices")
    public ApiResponse<List<SupportDto.PublicNotice>> getNotices() {
        return ApiResponse.success(orgSupportService.getPublicNotices());
    }
}

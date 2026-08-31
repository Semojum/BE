package com.semojum.backend.domain.rule.controller;

import com.semojum.backend.domain.rule.dto.BrailleRuleDto;
import com.semojum.backend.domain.rule.service.BrailleRuleSearchService;
import com.semojum.backend.global.exception.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 점자 규정 검색 — 에디터에서 점역사가 규정을 찾아본다.
 * 로그인만 하면 되고(SecurityConfig 의 anyRequest().authenticated()) 역할 제한은 없다.
 */
@RestController
@RequestMapping("/api/rules")
@RequiredArgsConstructor
public class BrailleRuleController {

    private final BrailleRuleSearchService searchService;

    /**
     * 규정 검색. q 는 rule_id·조문 경로·규정명·본문 어디에 걸려도 결과에 포함되고,
     * 공백으로 나눈 여러 단어는 모두 걸린 것만 남긴다(AND).
     * q 없이 호출하면 전체를 원문 순서로 준다 — 목차처럼 훑는 용도.
     *
     * @param publisher MCST | NLD | NISE (대소문자 무시)
     * @param part      MCST 편 — 기본 · 한글 · 수학 · 과학 · 외국어
     */
    @GetMapping
    public ApiResponse<BrailleRuleDto.Page> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String publisher,
            @RequestParam(required = false) String part,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(searchService.search(q, publisher, part, page, size));
    }
}

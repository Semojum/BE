package com.semojum.backend.domain.rule.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** 점자 규정 검색 응답 */
public class BrailleRuleDto {

    /**
     * 검색 결과 한 건. section 은 rule_trail 의 section 과 <b>같은 " · " 합본 형식</b>이라
     * 에디터 규정 배지에서 본 문자열이 검색 결과에도 그대로 보인다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            String ruleId,
            String publisherCode,   // MCST | NLD | NISE
            String publisher,       // 문화체육관광부 | 국립장애인도서관 | 국립특수교육원
            String source,          // 한국 점자 규정 | 점자 도서 제작 지침 | 점자 자료 제작 지침
            int version,            // 2024 | 2025
            String section,         // "한글 점자 · 제5장. 숫자와 기호 · 제11절. … · 제40항"
            String ruleName,
            String contents,
            String tag,             // default_tag (없으면 미포함)
            String matchedIn        // ruleId | ruleName | section | contents — FE 하이라이트 대상
    ) {}

    /** 문의 목록(T1-9)과 같은 페이지 응답 규약 */
    public record Page(
            List<Item> items,
            int page, int size,
            long totalElements, int totalPages
    ) {}
}

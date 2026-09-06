package com.semojum.backend.domain.job.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 업로드 시 고르는 조판 옵션 — 그 작업의 점자 판면을 어떻게 짤지 결정한다.
 * 저장은 {@code jobs.layout_options}(jsonb) 한 칸. 기획이 확정 전이라 항목이 바뀌어도
 * 마이그레이션 없이 늘리고 줄일 수 있게 했다.
 *
 * <p><b>값이 없으면 기본값</b>: 점자 도서 관행(32칸 × 26줄, 페이지행은 홀수 면)을 따른다 —
 * {@code BrailleAssist.Options.defaults()}와 같은 값이다.
 *
 * <p>모르는 필드는 무시한다(JsonIgnoreProperties) — FE가 먼저 새 항목을 보내도 기존 BE가 죽지 않게.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LayoutOptions(
        Integer cellsPerLine,           // 한 줄 칸 수 (기본 32)
        Integer linesPerPage,           // 한 면 줄 수 (기본 26)
        String pageNumberLine,          // 페이지 번호 표시줄: odd(홀수 면만) | every(모든 면) | none(넣지 않음)
        Integer coverPages,             // 표지로 건너뛸 면 수 (기본 0) — 앞에서 이 수만큼 번호를 매기지 않는다
        Integer sourcePageStart,        // 원본 쪽 번호 시작 (기본 1) — 올린 문서 첫 쪽이 실제 몇 쪽인지
        Integer braillePageStart,       // 점자 면 번호 시작 (기본 1)
        Boolean showSourcePageNumber,   // 페이지행 왼쪽에 원본 쪽 번호 (기본 true)
        Boolean showBraillePageNumber,  // 페이지행 오른쪽에 점자 면 번호 (기본 true)
        // 원본 쪽이 바뀌는 자리의 변경선 (기본 true). 끄면 화면과 .brf 둘 다에서 사라진다 —
        // 종전엔 이 스위치가 없어 showSourcePageNumber를 꺼야만 덩달아 꺼졌다(2026-09-03 추가)
        Boolean showChangeLine,
        String footerAlign,             // 꼬리말 정렬: center(가운데) | right(우측). 기본 center
        String editScope,               // 판면 수정 시 기본 적용 범위: all(이후 전부) | page(그 면만). 기본 all
        Boolean advancedAi              // 고급 점역 사용 여부 (기본 false) — AI 요청에 실린다
) {
    public static final int DEFAULT_CELLS_PER_LINE = 32;
    public static final int DEFAULT_LINES_PER_PAGE = 26;
    public static final String DEFAULT_PAGE_NUMBER_LINE = "odd";
    public static final String DEFAULT_FOOTER_ALIGN = "center";
    public static final String DEFAULT_EDIT_SCOPE = "all";

    /** 빠진 값을 기본값으로 채운 사본. 저장·사용 모두 이 형태로만 다뤄 null 분기를 없앤다. */
    public LayoutOptions withDefaults() {
        return new LayoutOptions(
                cellsPerLine == null ? DEFAULT_CELLS_PER_LINE : cellsPerLine,
                linesPerPage == null ? DEFAULT_LINES_PER_PAGE : linesPerPage,
                pageNumberLine == null ? DEFAULT_PAGE_NUMBER_LINE : pageNumberLine,
                coverPages == null ? 0 : coverPages,
                sourcePageStart == null ? 1 : sourcePageStart,
                braillePageStart == null ? 1 : braillePageStart,
                showSourcePageNumber == null || showSourcePageNumber,
                showBraillePageNumber == null || showBraillePageNumber,
                showChangeLine == null || showChangeLine,
                footerAlign == null ? DEFAULT_FOOTER_ALIGN : footerAlign,
                editScope == null ? DEFAULT_EDIT_SCOPE : editScope,
                advancedAi != null && advancedAi);
    }

    /**
     * 이 값 위에 {@code patch}의 <b>비어 있지 않은 항목만</b> 덮어쓴 사본 (부분 갱신용, V32).
     *
     * <p>{@code PATCH /api/jobs/{jobId}/options}가 쓴다 — 에디터 조판 설정 모달이 바꾼 항목만
     * 보내도 나머지가 유지되게. null은 "그대로 두라"는 뜻이라 <b>이 방식으로는 값을 비울 수 없지만</b>,
     * 12개 항목 전부 기본값이 있는 스칼라라 비울 일 자체가 없다(꼬리말만 지울 수 있고, 그건
     * {@code jobs.footer_text}라 여기 없다).
     *
     * <p>받는 쪽(this)은 이미 {@link #withDefaults()}를 거친 완전한 형태라고 본다 —
     * {@code Job.resolveLayoutOptions()}가 항상 그렇게 준다.
     */
    public LayoutOptions merge(LayoutOptions patch) {
        if (patch == null) return this;
        return new LayoutOptions(
                patch.cellsPerLine() != null ? patch.cellsPerLine() : cellsPerLine,
                patch.linesPerPage() != null ? patch.linesPerPage() : linesPerPage,
                patch.pageNumberLine() != null ? patch.pageNumberLine() : pageNumberLine,
                patch.coverPages() != null ? patch.coverPages() : coverPages,
                patch.sourcePageStart() != null ? patch.sourcePageStart() : sourcePageStart,
                patch.braillePageStart() != null ? patch.braillePageStart() : braillePageStart,
                patch.showSourcePageNumber() != null ? patch.showSourcePageNumber() : showSourcePageNumber,
                patch.showBraillePageNumber() != null ? patch.showBraillePageNumber() : showBraillePageNumber,
                patch.showChangeLine() != null ? patch.showChangeLine() : showChangeLine,
                patch.footerAlign() != null ? patch.footerAlign() : footerAlign,
                patch.editScope() != null ? patch.editScope() : editScope,
                patch.advancedAi() != null ? patch.advancedAi() : advancedAi
        ).withDefaults();
    }

    /** 옵션 없이 만들어진 기존 작업용 — 구 insert_page_number만 반영한 기본값 */
    public static LayoutOptions legacy(boolean insertPageNumber) {
        return new LayoutOptions(null, null, insertPageNumber ? "odd" : "none",
                null, null, null, null, null, null, null, null, null).withDefaults();
    }
}

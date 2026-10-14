package com.semojum.backend.domain.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.semojum.backend.domain.job.dto.BrailleOptions;

import java.util.Map;

/**
 * D6 「기본 점역 옵션 설정」 화면 한 장 — 계정 기본값 전체 (2026-10-14).
 *
 * <p>화면이 한 장이라 API도 평평한 한 덩어리로 주고받는다. 저장은 둘로 나뉜다 —
 * 점역 옵션 8개는 {@code users.braille_options}(양식·작업으로 통째 복사되는 묶음),
 * 점역 양식과 페이지행 면은 계정 칸 두 개({@code default_form_id}·{@code default_page_number_line}).
 * 페이지행 면을 점역 옵션 묶음에 넣으면 조판 옵션의 {@code pageNumberLine}과 작업·양식마다 겹친다.
 *
 * <p>모르는 필드는 무시한다 — 지운 항목(점역문서·역점역문서 형식)을 구 클라이언트가 보내도 된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DefaultOptions(
        // 점역
        String hangulGrade,
        String englishGrade,
        String brailleRule,
        String mixedRule,
        String formId,             // 점역 양식 — 「열기」 창에서 처음 골라져 있을 양식. 기본 builtin:MIDHIGH_TEXTBOOK
        // 조판
        String consecutiveSpace,
        String lineBreakUnit,
        String choiceLayout,
        Map<String, BrailleOptions.VisualDescription> visualDescriptions,
        String pageNumberLine      // 페이지행 면: odd(홀수) | every(모든). 기본 odd
) {
    public static final String DEFAULT_FORM_ID = "builtin:MIDHIGH_TEXTBOOK";
    public static final String DEFAULT_PAGE_NUMBER_LINE = "odd";

    public BrailleOptions braille() {
        return new BrailleOptions(hangulGrade, englishGrade, brailleRule, mixedRule,
                consecutiveSpace, lineBreakUnit, choiceLayout, visualDescriptions);
    }

    public static DefaultOptions of(BrailleOptions b, String formId, String pageNumberLine) {
        return new DefaultOptions(b.hangulGrade(), b.englishGrade(), b.brailleRule(), b.mixedRule(),
                formId == null ? DEFAULT_FORM_ID : formId,
                b.consecutiveSpace(), b.lineBreakUnit(), b.choiceLayout(), b.visualDescriptions(),
                pageNumberLine == null ? DEFAULT_PAGE_NUMBER_LINE : pageNumberLine);
    }
}

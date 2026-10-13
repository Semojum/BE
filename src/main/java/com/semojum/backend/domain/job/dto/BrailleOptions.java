package com.semojum.backend.domain.job.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;

import java.util.List;

/**
 * 점역 옵션 — 묵자를 점자로 옮길 때의 규칙 (D6 「기본 점역 옵션 설정」, 기획서 5-1).
 *
 * <p>{@link LayoutOptions}와 짝이지만 다루는 것이 다르다. 조판 옵션은 <b>판면을 어떻게 짜는지</b>
 * (칸 수·줄 수·페이지행)이고, 이쪽은 <b>글자를 어떻게 옮기는지</b>(약자·규정·줄바꿈 단위)다.
 *
 * <p>저장되는 곳이 둘이다 — {@code users.braille_options}는 계정 기본값이고, 업로드 때 그 사본이
 * 작업으로 복사된다. 기본값을 나중에 고쳐도 이미 만든 작업이 바뀌지 않아야 하기 때문이다.
 *
 * <p><b>점역 자체는 FE 엔진이 한다</b>(V3.1). BE는 값을 보관하고 돌려주는 역할이라 의미를 해석하지
 * 않는다 — {@code brailleFormat}·{@code textFormat}은 FE 다운로드가 쓰는 값이라 더욱 그렇다.
 *
 * <p>모르는 필드는 무시한다(JsonIgnoreProperties) — FE가 먼저 새 항목을 보내도 BE가 죽지 않게.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BrailleOptions(
        String hangulGrade,        // 한글: grade1(정자) | grade2(약자). 기본 grade2
        String brailleRule,        // 점역 규정: korean(한글) | english(영어). 기본 korean
        String mixedRule,          // 한영 혼용 규정: korean(한국어) | english(영어). 기본 korean
        String consecutiveSpace,   // 연속 공백: keep(그대로) | single(하나로). 기본 single
        String lineBreakUnit,      // 줄바꿈 단위: syllable(음절) | word(어절). 기본 syllable
        String choiceLayout,       // 선택지 배치: onePerLine(한 줄에 하나씩) | merge(짧으면 합쳐 적기). 기본 merge
        String brailleFormat,      // 점역문서 형식: .brf. FE 다운로드가 쓴다 — BE는 보관만
        String textFormat          // 역점역문서 형식: .txt. 위와 같다
) {
    public static final String DEFAULT_HANGUL_GRADE = "grade2";
    public static final String DEFAULT_BRAILLE_RULE = "korean";
    public static final String DEFAULT_MIXED_RULE = "korean";
    public static final String DEFAULT_CONSECUTIVE_SPACE = "single";
    public static final String DEFAULT_LINE_BREAK_UNIT = "syllable";
    public static final String DEFAULT_CHOICE_LAYOUT = "merge";
    public static final String DEFAULT_BRAILLE_FORMAT = ".brf";
    public static final String DEFAULT_TEXT_FORMAT = ".txt";

    /** 공장 기본값 — 기획서 5-1의 기본값. 한 번도 저장하지 않은 계정이 받는 값이다. */
    public static BrailleOptions defaults() {
        return new BrailleOptions(null, null, null, null, null, null, null, null).withDefaults();
    }

    /** 빠진 값을 기본값으로 채운 사본. 저장·응답 모두 이 형태라 읽는 쪽에 null 분기가 없다. */
    public BrailleOptions withDefaults() {
        return new BrailleOptions(
                hangulGrade == null ? DEFAULT_HANGUL_GRADE : hangulGrade,
                brailleRule == null ? DEFAULT_BRAILLE_RULE : brailleRule,
                mixedRule == null ? DEFAULT_MIXED_RULE : mixedRule,
                consecutiveSpace == null ? DEFAULT_CONSECUTIVE_SPACE : consecutiveSpace,
                lineBreakUnit == null ? DEFAULT_LINE_BREAK_UNIT : lineBreakUnit,
                choiceLayout == null ? DEFAULT_CHOICE_LAYOUT : choiceLayout,
                brailleFormat == null ? DEFAULT_BRAILLE_FORMAT : brailleFormat,
                textFormat == null ? DEFAULT_TEXT_FORMAT : textFormat);
    }

    /**
     * 기본값을 채운 뒤 값을 검증한 사본. 범위 밖이면 COMMON4000.
     *
     * <p>막는 이유: BE는 이 값을 해석하지 않으므로 오타가 들어와도 저장은 성공한다. 그러면 FE
     * 점역 엔진이 모르는 값을 만나 조용히 기본 동작으로 떨어지고, 사용자는 설정이 먹었다고 믿는다.
     * 조판 옵션을 업로드에서 막는 것과 같은 이유다.
     */
    public BrailleOptions validated() {
        BrailleOptions o = withDefaults();
        boolean valid = List.of("grade1", "grade2").contains(o.hangulGrade())
                && List.of("korean", "english").contains(o.brailleRule())
                && List.of("korean", "english").contains(o.mixedRule())
                && List.of("keep", "single").contains(o.consecutiveSpace())
                && List.of("syllable", "word").contains(o.lineBreakUnit())
                && List.of("onePerLine", "merge").contains(o.choiceLayout())
                && List.of(".brf").contains(o.brailleFormat())
                && List.of(".txt").contains(o.textFormat());
        if (!valid) throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        return o;
    }
}

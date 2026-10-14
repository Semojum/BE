package com.semojum.backend.domain.job.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 점역 옵션 — 묵자를 점자로 옮길 때의 규칙 (D6 「기본 점역 옵션 설정」, 기획서 5-1).
 *
 * <p>{@link LayoutOptions}와 짝이지만 다루는 것이 다르다. 조판 옵션은 <b>판면을 어떻게 짜는지</b>
 * (칸 수·줄 수·페이지행)이고, 이쪽은 <b>글자를 어떻게 옮기는지</b>(약자·규정·줄바꿈 단위)다.
 * D6 화면은 연속 공백·줄바꿈·선택지 배치를 「조판」 묶음에 넣었지만 값은 여기 둔다 — 판면 칸·줄이
 * 아니라 글을 옮기는 방식이라 양식·작업에서 이 묶음과 함께 움직인다.
 *
 * <p>저장되는 곳이 셋이다 — 계정 기본값({@code users.braille_options}), 양식({@code braille_forms}),
 * 작업({@code jobs.braille_options}). 업로드 때 앞의 둘에서 작업으로 <b>복사</b>된다.
 *
 * <p><b>점역 자체는 앱 엔진이 한다</b>(V3.1). BE는 값을 보관하고 돌려줄 뿐 의미를 해석하지 않는다.
 * 예외는 AI 요청에 실리는 셋 — {@code hangulGrade}·{@code englishGrade}·{@code choiceLayout}
 * (proto {@code korean_grade}·{@code english_grade}·{@code split_choices}).
 *
 * <p>모르는 필드는 무시한다(JsonIgnoreProperties) — 앱이 먼저 새 항목을 보내도 BE가 죽지 않고,
 * 지운 항목(점역문서·역점역문서 형식, 2026-10-14)이 예전 행에 남아 있어도 읽힌다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BrailleOptions(
        String hangulGrade,        // 한글: grade1(정자) | grade2(약자). 기본 grade2
        String englishGrade,       // 영문: grade1(정자) | grade2(약자). 기본 grade2 (2026-10-14 P0로 올라옴)
        String brailleRule,        // 점역 규정: korean(한글) | english(영어). 기본 korean
        String mixedRule,          // 한영 혼용 규정: korean(한국어) | english(영어). 기본 korean
        String consecutiveSpace,   // 연속 공백: keep(그대로) | single(하나로). 기본 single
        String lineBreakUnit,      // 줄바꿈 단위: syllable(음절) | word(어절). 기본 syllable
        String choiceLayout,       // 선택지 배치: onePerLine(한 줄에 하나씩) | merge(짧으면 합치기). 기본 merge
        // 시각 자료 설명 유형 (D9) — 레이아웃 유형마다 고를 수 있는 설명과 기본값. 키는 VISUAL_TYPES
        Map<String, VisualDescription> visualDescriptions
) {
    public static final String DEFAULT_HANGUL_GRADE = "grade2";
    public static final String DEFAULT_ENGLISH_GRADE = "grade2";
    public static final String DEFAULT_BRAILLE_RULE = "korean";
    public static final String DEFAULT_MIXED_RULE = "korean";
    public static final String DEFAULT_CONSECUTIVE_SPACE = "single";
    public static final String DEFAULT_LINE_BREAK_UNIT = "syllable";
    public static final String DEFAULT_CHOICE_LAYOUT = "merge";

    /**
     * 시각 자료 한 유형의 설명 설정 — D9 한 줄(체크박스들 + 「기본」 드롭다운).
     *
     * @param options       대체 초안 판에 띄울 설명들(체크한 것)
     * @param defaultOption 처음 고를 설명. {@code options} 안에 있어야 한다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VisualDescription(List<String> options, String defaultOption) {}

    /**
     * 레이아웃 유형별로 고를 수 있는 설명 — 피그마 D9 「시각 자료 설명 유형」(2026-10-14).
     *
     * <p>⚠️ 이 이름들은 <b>AI 대체 초안의 {@code label}과 같은 글자여야</b> 앱이 짝을 맞출 수 있다.
     * AI 팀과 아직 맞추지 않았다 — 「상세」「별책 참조」「관행형」「상향식」이 AI가 실제로 주는 이름과
     * 다르면 여기 한 곳만 고치면 된다.
     */
    public static final Map<String, List<String>> VISUAL_TYPES = orderedMap(
            "figure",  List.of("생략", "짧은 제목", "개조식", "상세", "줄글", "별책 참조"),   // 그림
            "table",   List.of("테두리+구분선", "테두리만", "행열 바꿈", "풀어쓰기"),        // 표
            "chart",   List.of("생략", "짧은 제목", "개조식", "상세", "줄글", "별책 참조"),   // 차트
            "diagram", List.of("생략", "짧은 제목", "개조식", "상세", "관행형", "상향식"),    // 도표
            "cartoon", List.of("생략", "개조식", "줄글", "별책 참조"));                    // 만화

    /** D9 공장 기본값 — 피그마에서 체크된 것과 「기본」 */
    public static final Map<String, VisualDescription> DEFAULT_VISUAL_DESCRIPTIONS = orderedMap(
            "figure",  new VisualDescription(List.of("짧은 제목", "개조식", "상세", "줄글"), "개조식"),
            "table",   new VisualDescription(List.of("테두리+구분선", "테두리만", "행열 바꿈", "풀어쓰기"), "테두리+구분선"),
            "chart",   new VisualDescription(List.of("짧은 제목", "개조식", "상세"), "개조식"),
            "diagram", new VisualDescription(List.of("짧은 제목", "개조식", "상세", "관행형"), "개조식"),
            "cartoon", new VisualDescription(List.of("개조식", "줄글"), "개조식"));

    /** 공장 기본값 — 기획서 5-1의 기본값. 한 번도 저장하지 않은 계정이 받는 값이다. */
    public static BrailleOptions defaults() {
        return new BrailleOptions(null, null, null, null, null, null, null, null).withDefaults();
    }

    /** 빠진 값을 기본값으로 채운 사본. 저장·응답 모두 이 형태라 읽는 쪽에 null 분기가 없다. */
    public BrailleOptions withDefaults() {
        Map<String, VisualDescription> visual = new LinkedHashMap<>(DEFAULT_VISUAL_DESCRIPTIONS);
        if (visualDescriptions != null) visual.putAll(visualDescriptions);   // 안 보낸 유형은 기본값 그대로
        return new BrailleOptions(
                hangulGrade == null ? DEFAULT_HANGUL_GRADE : hangulGrade,
                englishGrade == null ? DEFAULT_ENGLISH_GRADE : englishGrade,
                brailleRule == null ? DEFAULT_BRAILLE_RULE : brailleRule,
                mixedRule == null ? DEFAULT_MIXED_RULE : mixedRule,
                consecutiveSpace == null ? DEFAULT_CONSECUTIVE_SPACE : consecutiveSpace,
                lineBreakUnit == null ? DEFAULT_LINE_BREAK_UNIT : lineBreakUnit,
                choiceLayout == null ? DEFAULT_CHOICE_LAYOUT : choiceLayout,
                visual);
    }

    /**
     * 이 값 위에 {@code patch}의 <b>비어 있지 않은 항목만</b> 덮어쓴 사본.
     *
     * <p>업로드가 쓴다 — 양식을 고르면 그 값이 바탕이 되고, 업로드 창에서 따로 건드린 항목만
     * 그 위에 덮인다. 시각 자료 설명은 <b>보낸 유형만</b> 통째로 바뀐다(유형 안의 체크 목록을
     * 항목별로 섞지 않는다 — 목록과 기본값이 서로 맞아야 해서다).
     */
    public BrailleOptions merge(BrailleOptions patch) {
        if (patch == null) return this;
        Map<String, VisualDescription> visual = new LinkedHashMap<>();
        if (visualDescriptions != null) visual.putAll(visualDescriptions);
        if (patch.visualDescriptions() != null) visual.putAll(patch.visualDescriptions());
        return new BrailleOptions(
                patch.hangulGrade() != null ? patch.hangulGrade() : hangulGrade,
                patch.englishGrade() != null ? patch.englishGrade() : englishGrade,
                patch.brailleRule() != null ? patch.brailleRule() : brailleRule,
                patch.mixedRule() != null ? patch.mixedRule() : mixedRule,
                patch.consecutiveSpace() != null ? patch.consecutiveSpace() : consecutiveSpace,
                patch.lineBreakUnit() != null ? patch.lineBreakUnit() : lineBreakUnit,
                patch.choiceLayout() != null ? patch.choiceLayout() : choiceLayout,
                visual.isEmpty() ? null : visual
        ).withDefaults();
    }

    /**
     * 기본값을 채운 뒤 값을 검증한 사본. 범위 밖이면 COMMON4000.
     *
     * <p>막는 이유: BE는 이 값을 해석하지 않으므로 오타가 들어와도 저장은 성공한다. 그러면 앱
     * 점역 엔진이 모르는 값을 만나 조용히 기본 동작으로 떨어지고, 사용자는 설정이 먹었다고 믿는다.
     */
    public BrailleOptions validated() {
        BrailleOptions o = withDefaults();
        boolean valid = List.of("grade1", "grade2").contains(o.hangulGrade())
                && List.of("grade1", "grade2").contains(o.englishGrade())
                && List.of("korean", "english").contains(o.brailleRule())
                && List.of("korean", "english").contains(o.mixedRule())
                && List.of("keep", "single").contains(o.consecutiveSpace())
                && List.of("syllable", "word").contains(o.lineBreakUnit())
                && List.of("onePerLine", "merge").contains(o.choiceLayout())
                && o.visualDescriptions().entrySet().stream().allMatch(BrailleOptions::validVisual);
        if (!valid) throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        return o;
    }

    /** 알려진 유형이고, 고른 설명이 그 유형의 선택지 안에 있고, 기본값이 고른 것 중 하나여야 한다 */
    private static boolean validVisual(Map.Entry<String, VisualDescription> e) {
        List<String> vocabulary = VISUAL_TYPES.get(e.getKey());
        VisualDescription v = e.getValue();
        if (vocabulary == null || v == null || v.options() == null || v.options().isEmpty()) return false;
        if (new HashSet<>(v.options()).size() != v.options().size()) return false;   // 중복 체크
        return vocabulary.containsAll(v.options()) && v.options().contains(v.defaultOption());
    }

    @SuppressWarnings("unchecked")
    private static <V> Map<String, V> orderedMap(Object... keyValues) {
        Map<String, V> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) m.put((String) keyValues[i], (V) keyValues[i + 1]);
        return java.util.Collections.unmodifiableMap(m);
    }
}

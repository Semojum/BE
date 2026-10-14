package com.semojum.backend.domain.user;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.job.dto.BrailleOptions;
import com.semojum.backend.domain.job.dto.BrailleOptions.VisualDescription;
import com.semojum.backend.domain.user.dto.DefaultOptions;
import com.semojum.backend.domain.user.entity.BrailleForm;
import com.semojum.backend.domain.user.repository.BrailleFormRepository;
import com.semojum.backend.domain.user.service.BrailleOptionsService;
import com.semojum.backend.domain.user.service.BuiltinBrailleForms;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 계정별 기본 점역 옵션 — D6 「기본 점역 옵션 설정」 화면 한 장 (2026-10-14 개편 반영).
 *
 * <p>BE는 값의 의미를 해석하지 않는다(점역은 앱 엔진이 한다). 지킬 것은 셋이다 — 저장한 적 없는
 * 계정도 완전한 값을 받는 것, 모르는 값이 저장되지 않는 것, 기본 양식이 있는 양식이어야 하는 것.
 */
class BrailleOptionsTest {

    private UserRepository userRepository;
    private BrailleFormRepository formRepository;
    private BrailleOptionsService service;
    private User user;
    private final String userId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        formRepository = Mockito.mock(BrailleFormRepository.class);
        user = User.builder().loginId("semojum01").password("pw").build();
        ReflectionTestUtils.setField(user, "id", UUID.fromString(userId));
        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.of(user));

        BuiltinBrailleForms builtin = new BuiltinBrailleForms();
        builtin.load();
        service = new BrailleOptionsService(userRepository, formRepository, builtin);
    }

    /** D6에서 아무것도 안 고친 상태 — 보내는 항목 중 하나만 바꿔 쓴다 */
    private DefaultOptions request(String hangulGrade, String englishGrade, String formId, String pageNumberLine,
                                   Map<String, VisualDescription> visual) {
        return new DefaultOptions(hangulGrade, englishGrade, "korean", "korean", formId,
                "single", "syllable", "merge", visual, pageNumberLine);
    }

    private DefaultOptions request(String hangulGrade) {
        return request(hangulGrade, "grade2", null, null, null);
    }

    // ── 공장 기본값 ─────────────────────────────────────────────────

    /** 한 번도 저장하지 않은 계정 — null을 주면 앱이 빈 화면을 그린다 */
    @Test
    void 저장한_적_없으면_공장_기본값을_준다() {
        DefaultOptions o = service.get(userId);

        assertEquals("grade2", o.hangulGrade());      // 약자
        assertEquals("grade2", o.englishGrade());     // 영문 약자 (P0로 올라옴)
        assertEquals("korean", o.brailleRule());
        assertEquals("korean", o.mixedRule());
        assertEquals("builtin:MIDHIGH_TEXTBOOK", o.formId());   // 점역 양식: 중·고 교과서
        assertEquals("single", o.consecutiveSpace());
        assertEquals("syllable", o.lineBreakUnit());
        assertEquals("merge", o.choiceLayout());
        assertEquals("odd", o.pageNumberLine());      // 페이지행 면: 홀수
    }

    /** D9 기본값 — 피그마에서 체크된 것과 「기본」 */
    @Test
    void 시각_자료_설명은_다섯_유형의_기본값이_채워져_있다() {
        Map<String, VisualDescription> v = service.get(userId).visualDescriptions();

        assertEquals(List.of("figure", "table", "chart", "diagram", "cartoon"), List.copyOf(v.keySet()));
        assertEquals("개조식", v.get("figure").defaultOption());
        assertEquals("테두리+구분선", v.get("table").defaultOption());
        assertEquals(List.of("개조식", "줄글"), v.get("cartoon").options());
    }

    // ── 저장 ─────────────────────────────────────────────────────────

    @Test
    void 저장한_값을_그대로_돌려준다() {
        service.update(userId, request("grade1", "grade1", "builtin:EXAM", "every", null));
        DefaultOptions o = service.get(userId);

        assertEquals("grade1", o.hangulGrade());
        assertEquals("grade1", o.englishGrade());
        assertEquals("builtin:EXAM", o.formId());
        assertEquals("every", o.pageNumberLine());
    }

    /** 창이 항목 전부를 보여주므로 통째로 덮어쓴다 — 빠진 항목은 「그대로」가 아니라 기본값이다 */
    @Test
    void 빠진_항목은_기본값으로_덮인다() {
        service.update(userId, request("grade1", "grade1", "builtin:EXAM", "every", null));

        DefaultOptions saved = service.update(userId, new DefaultOptions(
                null, null, null, null, null, null, null, null, null, null));

        assertEquals("grade2", saved.hangulGrade());
        assertEquals("builtin:MIDHIGH_TEXTBOOK", saved.formId());
        assertEquals("odd", saved.pageNumberLine());
    }

    /** 본문을 안 보내면 공장 기본값으로 저장한다 — D6 「기본값 복원」 뒤 「확인」이 이 모양이다 */
    @Test
    void 본문이_없으면_기본값으로_저장한다() {
        assertEquals("grade2", service.update(userId, null).hangulGrade());
    }

    /** 페이지행 면은 계정 칸에 따로 저장된다 — 점역 옵션 묶음에 넣으면 조판 옵션과 겹친다 */
    @Test
    void 페이지행_면과_양식은_계정_칸에_저장된다() {
        service.update(userId, request("grade2", "grade2", "builtin:WORKBOOK", "every", null));

        assertEquals("builtin:WORKBOOK", user.getDefaultFormId());
        assertEquals("every", user.getDefaultPageNumberLine());
    }

    // ── 검증 ─────────────────────────────────────────────────────────

    /** 오타가 저장되면 앱 점역 엔진이 조용히 기본 동작으로 떨어져 사용자가 속는다 */
    @Test
    void 모르는_값은_400이다() {
        for (DefaultOptions bad : new DefaultOptions[]{
                request("grade3"),
                request("grade2", "grade9", null, null, null),            // 영문 급수
                request("grade2", "grade2", null, "none", null),          // 페이지행 면은 홀수·모든뿐
                new DefaultOptions("grade2", "grade2", "japanese", "korean", null,
                        "single", "syllable", "merge", null, null),
                new DefaultOptions("grade2", "grade2", "korean", "korean", null,
                        "single", "sentence", "merge", null, null),
        }) {
            CustomException e = assertThrows(CustomException.class, () -> service.update(userId, bad));
            assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
        }
    }

    /** 지운 양식을 기본으로 남기면 「열기」 창이 없는 양식을 골라 둔 채 열린다 */
    @Test
    void 없는_양식은_기본_양식이_될_수_없다() {
        when(formRepository.findByIdAndUserId(any(UUID.class), any(UUID.class))).thenReturn(Optional.empty());

        for (String formId : new String[]{"builtin:NOPE", UUID.randomUUID().toString(), "중고교과서"}) {
            CustomException e = assertThrows(CustomException.class,
                    () -> service.update(userId, request("grade2", "grade2", formId, null, null)));
            assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode(), formId);
        }
    }

    @Test
    void 내_양식은_기본_양식이_될_수_있다() {
        UUID mine = UUID.randomUUID();
        when(formRepository.findByIdAndUserId(mine, UUID.fromString(userId)))
                .thenReturn(Optional.of(Mockito.mock(BrailleForm.class)));

        assertEquals(mine.toString(),
                service.update(userId, request("grade2", "grade2", mine.toString(), null, null)).formId());
    }

    // ── 시각 자료 설명 유형 (D9) ───────────────────────────────────────

    @Test
    void 시각_자료는_보낸_유형만_바뀌고_나머지는_기본값이다() {
        DefaultOptions saved = service.update(userId, request("grade2", "grade2", null, null,
                Map.of("figure", new VisualDescription(List.of("생략", "짧은 제목"), "짧은 제목"))));

        assertEquals("짧은 제목", saved.visualDescriptions().get("figure").defaultOption());
        assertEquals("테두리+구분선", saved.visualDescriptions().get("table").defaultOption());
    }

    /**
     * 유형마다 선택지가 다르다(표엔 「테두리만」, 도표엔 「관행형」). 다른 유형의 이름이나 오타가
     * 저장되면 앱이 대체 초안과 짝을 못 맞춘다.
     */
    @Test
    void 시각_자료_설정이_어긋나면_400이다() {
        for (Map<String, VisualDescription> bad : List.of(
                Map.of("poster", new VisualDescription(List.of("개조식"), "개조식")),          // 모르는 유형
                Map.of("table", new VisualDescription(List.of("개조식"), "개조식")),           // 표의 선택지가 아님
                Map.of("figure", new VisualDescription(List.of("개조식", "줄글"), "상세")),     // 기본값이 고른 것에 없음
                Map.of("figure", new VisualDescription(List.of(), "개조식")),                 // 하나도 안 고름
                Map.of("figure", new VisualDescription(List.of("개조식", "개조식"), "개조식"))  // 중복
        )) {
            CustomException e = assertThrows(CustomException.class,
                    () -> service.update(userId, request("grade2", "grade2", null, null, bad)), bad.toString());
            assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
        }
    }

    // ── 그 밖 ────────────────────────────────────────────────────────

    @Test
    void 없는_회원은_USER4001이다() {
        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        CustomException e = assertThrows(CustomException.class, () -> service.get(userId));
        assertEquals(ErrorCode.USER_NOT_FOUND, e.getErrorCode());
    }

    /** 지운 항목(점역문서·역점역문서 형식)이 예전 행에 남아 있어도 읽혀야 한다 */
    @Test
    void 예전에_저장한_형식_항목은_무시된다() throws Exception {
        BrailleOptions old = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"hangulGrade\":\"grade1\",\"brailleFormat\":\".brf\",\"textFormat\":\".txt\"}",
                BrailleOptions.class);

        assertEquals("grade1", old.withDefaults().hangulGrade());
    }
}

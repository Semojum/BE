package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.job.dto.BrailleOptions;
import com.semojum.backend.domain.job.dto.LayoutOptions;
import com.semojum.backend.domain.user.entity.BrailleForm;
import com.semojum.backend.domain.user.repository.BrailleFormRepository;
import com.semojum.backend.domain.user.service.BuiltinBrailleForms;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 업로드 시점의 옵션 결정 순서 (D2 「열기」).
 *
 * <pre>
 *   ① 양식을 골랐으면 그 양식의 값이 바탕
 *   ② 안 골랐으면 계정 기본값이 바탕
 *   ③ 개별 항목을 보냈으면 그 위에 덮는다
 * </pre>
 */
class UploadOptionsResolverTest {

    private BrailleFormRepository formRepository;
    private UploadOptionsResolver resolver;

    @BeforeEach
    void setUp() {
        formRepository = Mockito.mock(BrailleFormRepository.class);
        BuiltinBrailleForms builtin = new BuiltinBrailleForms();
        builtin.load();
        resolver = new UploadOptionsResolver(formRepository, builtin);
    }

    /** 계정 기본값을 grade1로 바꿔 둔 사용자 */
    private User userWithDefault(String hangulGrade) {
        User user = User.builder().loginId("semojum01").password("pw").build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.updateBrailleOptions(new BrailleOptions(hangulGrade, null, null, null, null, null, null, null)
                .withDefaults());
        return user;
    }

    // ── ② 양식을 안 골랐을 때 ────────────────────────────────────

    @Test
    void 양식을_안_고르면_계정_기본값이_바탕이다() {
        var r = resolver.resolve(userWithDefault("grade1"), null, null, null);

        assertEquals("grade1", r.brailleOptions().hangulGrade());
    }

    /** 한 번도 저장 안 한 계정이면 공장 기본값 */
    @Test
    void 계정_기본값이_없으면_공장_기본값이다() {
        User user = User.builder().loginId("semojum01").password("pw").build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

        var r = resolver.resolve(user, null, null, null);

        assertEquals("grade2", r.brailleOptions().hangulGrade());
        assertEquals(32, r.layoutOptions().cellsPerLine());
    }

    // ── ① 양식을 골랐을 때 ──────────────────────────────────────

    /** 양식이 계정 기본값을 이긴다 — 고른 쪽이 더 구체적인 의사표시다 */
    @Test
    void 기본_양식을_고르면_양식_값이_바탕이다() {
        var r = resolver.resolve(userWithDefault("grade1"), "builtin:MIDHIGH_TEXTBOOK", null, null);

        assertEquals("grade2", r.brailleOptions().hangulGrade());   // 계정 기본값 grade1이 아니라 양식 값
        assertEquals(26, r.layoutOptions().linesPerPage());
    }

    @Test
    void 사용자_양식도_바탕이_된다() {
        User user = userWithDefault("grade1");
        BrailleForm form = BrailleForm.builder()
                .userId(user.getId()).name("중학 수학 (28줄)")
                .layoutOptions(new LayoutOptions(32, 28, null, null, null, null, null, null, null, null, null, null)
                        .withDefaults())
                .brailleOptions(new BrailleOptions(null, null, null, null, null, "word", null, null).withDefaults())
                .build();
        UUID formId = UUID.randomUUID();
        ReflectionTestUtils.setField(form, "id", formId);
        when(formRepository.findByIdAndUserId(any(UUID.class), any(UUID.class))).thenReturn(Optional.of(form));

        var r = resolver.resolve(user, formId.toString(), null, null);

        assertEquals(28, r.layoutOptions().linesPerPage());
        assertEquals("word", r.brailleOptions().lineBreakUnit());
    }

    // ── ③ 개별 항목이 덮는다 ────────────────────────────────────

    /** 양식을 고른 뒤 창에서 한 항목만 바꾼 경우 — 그 항목만 덮이고 나머지는 양식 값이다 */
    @Test
    void 개별_항목이_양식_위에_덮인다() {
        var r = resolver.resolve(userWithDefault("grade2"), "builtin:MIDHIGH_TEXTBOOK",
                new LayoutOptions(40, null, null, null, null, null, null, null, null, null, null, null),
                new BrailleOptions("grade1", null, null, null, null, null, null, null));

        assertEquals(40, r.layoutOptions().cellsPerLine());      // 덮임
        assertEquals(26, r.layoutOptions().linesPerPage());      // 양식 값 유지
        assertEquals("grade1", r.brailleOptions().hangulGrade()); // 덮임
        assertEquals("syllable", r.brailleOptions().lineBreakUnit()); // 양식 값 유지
    }

    // ── 거절 ────────────────────────────────────────────────────

    @Test
    void 없는_기본_양식은_400이다() {
        CustomException e = assertThrows(CustomException.class, () ->
                resolver.resolve(userWithDefault("grade2"), "builtin:NOPE", null, null));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void 남의_양식이거나_없는_양식은_400이다() {
        when(formRepository.findByIdAndUserId(any(UUID.class), any(UUID.class))).thenReturn(Optional.empty());

        CustomException e = assertThrows(CustomException.class, () ->
                resolver.resolve(userWithDefault("grade2"), UUID.randomUUID().toString(), null, null));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    /** UUID가 아닌 값이 와도 500으로 터지지 않게 */
    @Test
    void 모양이_틀린_formId는_400이다() {
        CustomException e = assertThrows(CustomException.class, () ->
                resolver.resolve(userWithDefault("grade2"), "중고교과서", null, null));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    /** 업로드 경로에서도 점역 옵션 값을 검증한다 — 저장 경로가 셋이어도 규칙은 하나다 */
    @Test
    void 모르는_점역_옵션_값은_400이다() {
        CustomException e = assertThrows(CustomException.class, () ->
                resolver.resolve(userWithDefault("grade2"), null, null,
                        new BrailleOptions("grade9", null, null, null, null, null, null, null)));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    /** 빈 문자열 formId는 "안 골랐다"로 본다 — 폼 필드는 빈 값으로 오기 쉽다 */
    @Test
    void 빈_formId는_안_고른_것이다() {
        var r = resolver.resolve(userWithDefault("grade1"), "", null, null);

        assertEquals("grade1", r.brailleOptions().hangulGrade());
    }

    /**
     * 회귀: 바탕을 구 insert_page_number 환산값으로 잡아(2026-10-13), 앱이 pageNumberLine을 안 보내면
     * 페이지행이 none으로 빠졌다. 바탕은 공장 기본값(홀수)이어야 한다.
     */
    @Test
    void 아무것도_안_보내면_페이지행은_홀수다() {
        var r = resolver.resolve(userWithDefault("grade2"), null,
                new LayoutOptions(null, null, null, null, null, null, null, null, null, null, null, null), null);

        assertEquals("odd", r.layoutOptions().pageNumberLine());
    }
}

package com.semojum.backend.domain.user;

import com.semojum.backend.domain.job.dto.BrailleOptions;
import com.semojum.backend.domain.job.dto.LayoutOptions;
import com.semojum.backend.domain.user.dto.BrailleFormDto;
import com.semojum.backend.domain.user.entity.BrailleForm;
import com.semojum.backend.domain.user.repository.BrailleFormRepository;
import com.semojum.backend.domain.user.service.BrailleFormService;
import com.semojum.backend.domain.user.service.BuiltinBrailleForms;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 점역 양식 CRUD (D7 · D7-2).
 *
 * <p>양식은 조판 옵션 + 점역 옵션 한 벌이다. 지킬 것은 셋이다 —
 * 기본 양식과 사용자 양식이 한 목록에 나오는 것, 기본 양식에 손대지 못하는 것, 이름이 겹치지 않는 것.
 */
class BrailleFormTest {

    private BrailleFormRepository repository;
    private BrailleFormService service;
    private final String userId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(BrailleFormRepository.class);
        BuiltinBrailleForms builtin = new BuiltinBrailleForms();
        builtin.load();   // 클래스패스 리소스에서 읽는다
        service = new BrailleFormService(repository, builtin);

        when(repository.findByUserIdOrderByCreatedAtAsc(any(UUID.class))).thenReturn(List.of());
        when(repository.save(any(BrailleForm.class))).thenAnswer(call -> {
            BrailleForm f = call.getArgument(0);
            ReflectionTestUtils.setField(f, "id", UUID.randomUUID());
            return f;
        });
    }

    private BrailleFormDto.SaveForm request(String name) {
        return new BrailleFormDto.SaveForm(name, "builtin:MIDHIGH_TEXTBOOK",
                new LayoutOptions(32, 28, null, null, null, null, null, null, null, null, null, null),
                new BrailleOptions("grade1", null, null, null, "word", null, null, null));
    }

    // ── 기본 양식 ────────────────────────────────────────────────

    /** 기획서 5장의 5종 — 화면은 드롭다운 하나라 사용자 양식과 섞어 준다 */
    @Test
    void 기본_양식_다섯이_목록에_있다() {
        List<BrailleFormDto.Form> items = service.list(userId).items();

        assertEquals(5, items.size());
        assertTrue(items.stream().allMatch(BrailleFormDto.Form::builtin));
        assertTrue(items.stream().anyMatch(f -> f.name().equals("중·고 교과서")));
    }

    /** 리소스에는 유형별 차이만 적는다 — 빠진 항목이 null로 나가면 FE가 빈 칸을 그린다 */
    @Test
    void 기본_양식도_값이_채워져_있다() {
        BrailleFormDto.Form midHigh = service.list(userId).items().stream()
                .filter(f -> f.formId().equals("builtin:MIDHIGH_TEXTBOOK")).findFirst().orElseThrow();

        assertEquals(32, midHigh.layoutOptions().cellsPerLine());
        assertEquals(26, midHigh.layoutOptions().linesPerPage());
        assertEquals("grade2", midHigh.brailleOptions().hangulGrade());
        assertNotNull(midHigh.layoutOptions().footerAlign());       // 안 적은 항목도 기본값으로
        assertNotNull(midHigh.brailleOptions().consecutiveSpace());
    }

    /** 기본 양식을 고치거나 지우면 되돌릴 길이 없다 */
    @Test
    void 기본_양식은_수정도_삭제도_못_한다() {
        CustomException edit = assertThrows(CustomException.class,
                () -> service.update(userId, "builtin:EXAM", request("바꾼 이름")));
        assertEquals(ErrorCode.COMMON_FORBIDDEN, edit.getErrorCode());

        CustomException remove = assertThrows(CustomException.class,
                () -> service.delete(userId, "builtin:EXAM"));
        assertEquals(ErrorCode.COMMON_FORBIDDEN, remove.getErrorCode());
    }

    // ── 저장 ─────────────────────────────────────────────────────

    @Test
    void 저장하면_보낸_값과_바탕_양식이_남는다() {
        BrailleFormDto.Form saved = service.create(userId, request("중학 수학 (28줄)"));

        assertEquals("중학 수학 (28줄)", saved.name());
        assertFalse(saved.builtin());
        assertEquals("builtin:MIDHIGH_TEXTBOOK", saved.baseFormId());
        assertEquals(28, saved.layoutOptions().linesPerPage());
        assertEquals("grade1", saved.brailleOptions().hangulGrade());
        assertEquals("word", saved.brailleOptions().lineBreakUnit());
    }

    /** 안 보낸 항목은 기본값으로 채워 저장한다 — 읽는 쪽에 null 분기를 안 남긴다 */
    @Test
    void 안_보낸_항목은_기본값으로_채운다() {
        BrailleFormDto.Form saved = service.create(userId, request("중학 수학 (28줄)"));

        assertEquals(32, saved.layoutOptions().cellsPerLine());
        assertEquals("odd", saved.layoutOptions().pageNumberLine());
        assertEquals("korean", saved.brailleOptions().brailleRule());
    }

    /** 목록에서 고르는 것이 이름뿐이라 같은 이름 둘은 구분할 수 없다 */
    @Test
    void 이름이_겹치면_COMMON4009다() {
        when(repository.existsByUserIdAndName(any(UUID.class), any())).thenReturn(true);

        CustomException e = assertThrows(CustomException.class,
                () -> service.create(userId, request("중학 수학 (28줄)")));
        assertEquals(ErrorCode.COMMON_NAME_DUPLICATE, e.getErrorCode());
    }

    @Test
    void 빈_이름은_400이다() {
        for (String name : new String[]{"", "   "}) {
            CustomException e = assertThrows(CustomException.class,
                    () -> service.create(userId, request(name)));
            assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
        }
    }

    /** 점역 옵션은 양식에 들어갈 때도 검증한다 — 저장 경로가 둘이어도 규칙은 하나다 */
    @Test
    void 모르는_점역_옵션_값은_400이다() {
        BrailleFormDto.SaveForm bad = new BrailleFormDto.SaveForm("시험지 변형", null, null,
                new BrailleOptions("grade9", null, null, null, null, null, null, null));

        CustomException e = assertThrows(CustomException.class, () -> service.create(userId, bad));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    // ── 수정·삭제 ────────────────────────────────────────────────

    @Test
    void 남의_양식은_찾지_못한다() {
        when(repository.findByIdAndUserId(any(UUID.class), any(UUID.class))).thenReturn(Optional.empty());

        CustomException e = assertThrows(CustomException.class,
                () -> service.update(userId, UUID.randomUUID().toString(), request("아무거나")));
        assertEquals(ErrorCode.COMMON_NOT_FOUND, e.getErrorCode());
    }

    /** UUID가 아닌 값이 오면 404 — 500으로 터지지 않게 */
    @Test
    void 이상한_formId는_404다() {
        CustomException e = assertThrows(CustomException.class,
                () -> service.delete(userId, "not-a-uuid"));
        assertEquals(ErrorCode.COMMON_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void 수정은_통째로_덮어쓴다() {
        BrailleForm existing = BrailleForm.builder()
                .userId(UUID.fromString(userId)).name("예전 이름").baseFormId(null)
                .layoutOptions(LayoutOptions.legacy(false)).brailleOptions(BrailleOptions.defaults())
                .build();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        when(repository.findByIdAndUserId(any(UUID.class), any(UUID.class))).thenReturn(Optional.of(existing));

        BrailleFormDto.Form updated = service.update(
                userId, existing.getId().toString(), request("새 이름"));

        assertEquals("새 이름", updated.name());
        assertEquals(28, updated.layoutOptions().linesPerPage());
        assertEquals("grade1", updated.brailleOptions().hangulGrade());
    }
}

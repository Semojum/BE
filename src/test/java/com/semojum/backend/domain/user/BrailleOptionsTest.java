package com.semojum.backend.domain.user;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.job.dto.BrailleOptions;
import com.semojum.backend.domain.user.service.BrailleOptionsService;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 계정별 기본 점역 옵션 (D6 「기본 점역 옵션 설정」).
 *
 * <p>BE는 값의 의미를 해석하지 않는다 — 점역은 FE 엔진이 한다. 그래서 여기서 지킬 것은 둘뿐이다:
 * 저장한 적 없는 계정도 완전한 값을 받는 것과, 모르는 값이 저장되지 않는 것.
 */
class BrailleOptionsTest {

    private BrailleOptionsService service(User user) {
        UserRepository userRepository = Mockito.mock(UserRepository.class);
        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.of(user));
        return new BrailleOptionsService(userRepository);
    }

    private User user() {
        return User.builder().loginId("semojum01").password("pw").build();
    }

    private BrailleOptions full(String hangulGrade) {
        return new BrailleOptions(hangulGrade, "korean", "korean", "single",
                "syllable", "merge", ".brf", ".txt");
    }

    /** 한 번도 저장하지 않은 계정 — null을 주면 FE가 빈 화면을 그린다 */
    @Test
    void 저장한_적_없으면_공장_기본값을_준다() {
        BrailleOptions o = service(user()).get(UUID.randomUUID().toString());

        assertEquals("grade2", o.hangulGrade());      // 약자
        assertEquals("korean", o.brailleRule());
        assertEquals("korean", o.mixedRule());
        assertEquals("single", o.consecutiveSpace()); // 하나로
        assertEquals("syllable", o.lineBreakUnit());  // 음절
        assertEquals("merge", o.choiceLayout());      // 짧으면 합쳐 적기
        assertEquals(".brf", o.brailleFormat());
        assertEquals(".txt", o.textFormat());
    }

    @Test
    void 저장한_값을_그대로_돌려준다() {
        User user = user();
        BrailleOptionsService service = service(user);

        service.update(UUID.randomUUID().toString(), full("grade1"));

        assertEquals("grade1", service.get(UUID.randomUUID().toString()).hangulGrade());
    }

    /** 창이 항목 전부를 보여주므로 통째로 덮어쓴다 — 빠진 항목은 "그대로"가 아니라 기본값이다 */
    @Test
    void 빠진_항목은_기본값으로_덮인다() {
        User user = user();
        BrailleOptionsService service = service(user);
        service.update(UUID.randomUUID().toString(), full("grade1"));

        BrailleOptions saved = service.update(UUID.randomUUID().toString(),
                new BrailleOptions(null, null, null, null, null, null, null, null));

        assertEquals("grade2", saved.hangulGrade());   // 이전 grade1이 남지 않는다
    }

    /** 오타가 저장되면 FE 점역 엔진이 조용히 기본 동작으로 떨어져 사용자가 속는다 */
    @Test
    void 모르는_값은_400이다() {
        BrailleOptionsService service = service(user());

        for (BrailleOptions bad : new BrailleOptions[]{
                full("grade3"),
                new BrailleOptions("grade2", "japanese", "korean", "single", "syllable", "merge", ".brf", ".txt"),
                new BrailleOptions("grade2", "korean", "korean", "single", "sentence", "merge", ".brf", ".txt"),
                new BrailleOptions("grade2", "korean", "korean", "single", "syllable", "merge", ".bbf", ".txt"),
        }) {
            CustomException e = assertThrows(CustomException.class,
                    () -> service.update(UUID.randomUUID().toString(), bad));
            assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
        }
    }

    /** 본문을 안 보내면 공장 기본값으로 저장한다 — D6 「기본값 복원」 뒤 「확인」이 이 모양이다 */
    @Test
    void 본문이_없으면_기본값으로_저장한다() {
        assertEquals("grade2",
                service(user()).update(UUID.randomUUID().toString(), null).hangulGrade());
    }

    /** 존재하지 않는 계정 */
    @Test
    void 없는_회원은_USER4001이다() {
        UserRepository userRepository = Mockito.mock(UserRepository.class);
        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        CustomException e = assertThrows(CustomException.class,
                () -> new BrailleOptionsService(userRepository).get(UUID.randomUUID().toString()));
        assertEquals(ErrorCode.USER_NOT_FOUND, e.getErrorCode());
    }
}

package com.semojum.backend.domain.result.service;

import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.grpc.BrailleGrpcClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 텍스트 → 점자 재점역. 예시 모양은 운영 mode c 실측(2026-09-30) —
 * 텍스트엔 {@code <!2칸>} 표식·표 틀(┌ └)이 있고, 점자 들여쓰기는 점자 빈칸(⠀)이다.
 */
class BrailleRetranslatorTest {

    static final String B = BrailleRetranslator.BRAILLE_SPACE;

    BrailleGrpcClient grpc;
    BrailleRetranslator retranslator;

    @BeforeEach
    void setUp() {
        grpc = mock(BrailleGrpcClient.class);
        // 가짜 AI: 입력을 [..]로 감싸 돌려준다 — 어떤 조각이 어떻게 이어졌는지 눈으로 보이게
        when(grpc.translateTexts(anyList(), anyLong())).thenAnswer(inv ->
                ((List<String>) inv.getArgument(0)).stream().map(t -> "[" + t + "]").toList());
        retranslator = new BrailleRetranslator(grpc);
    }

    @SuppressWarnings("unchecked")
    private List<String> sentToAi() {
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(grpc).translateTexts(captor.capture(), anyLong());
        return captor.getValue();
    }

    // ── 줄 번역 ──

    @Test
    void 들여쓰기_표식은_점자_빈칸으로_바꾸고_AI에_보내지_않는다() {
        Map<String, String> out = retranslator.translateLines(List.of("<!2칸>우리는 밥을 먹습니다."));
        assertEquals(List.of("우리는 밥을 먹습니다."), sentToAi());
        assertEquals(B + B + "[우리는 밥을 먹습니다.]", out.get("<!2칸>우리는 밥을 먹습니다."));
    }

    @Test
    void 두_칸_이상_공백은_같은_수의_점자_빈칸이다_표_열_간격() {
        Map<String, String> out = retranslator.translateLines(List.of("과일  개수"));
        assertEquals(List.of("과일", "개수"), sentToAi());
        assertEquals("[과일]" + B + B + "[개수]", out.get("과일  개수"));
    }

    @Test
    void 이백자를_넘는_줄은_띄어쓰기에서_끊고_점자_빈칸으로_잇는다() {
        String word = "가".repeat(99);
        String line = word + " " + word + " " + word; // 299자
        String out = retranslator.translateLines(List.of(line)).get(line);

        List<String> sent = sentToAi();
        assertTrue(sent.stream().allMatch(s -> s.length() <= BrailleRetranslator.MAX_CHARS), "모든 조각 ≤ 200자");
        assertEquals(List.of(word + " " + word, word), sent);
        assertEquals("[" + word + " " + word + "]" + B + "[" + word + "]", out);
    }

    @Test
    void 띄어쓰기_없는_긴_덩어리는_강제로_자르고_빈칸을_넣지_않는다() {
        String blob = "a".repeat(250);
        String out = retranslator.translateLines(List.of(blob)).get(blob);
        assertEquals(List.of("a".repeat(200), "a".repeat(50)), sentToAi());
        assertEquals("[" + "a".repeat(200) + "][" + "a".repeat(50) + "]", out, "한 낱말 안이라 이음 빈칸 없음");
    }

    @Test
    void 점역자주_표식은_점자의_점역자주_표로_바꾼다() {
        String line = "<!점역자주>그래프: 인구 변화<!/점역자주>";
        assertEquals("⠠⠄[그래프: 인구 변화]⠠⠄", retranslator.translateLines(List.of(line)).get(line));
        assertEquals(List.of("그래프: 인구 변화"), sentToAi(), "표식은 AI에 보내지 않는다");
    }

    @Test
    void 모르는_조판_표식은_AI에_보내지_않고_버린다() {
        String line = "<!굵게>제목";
        assertEquals("[제목]", retranslator.translateLines(List.of(line)).get(line));
    }

    @Test
    void 표_틀과_같은_줄은_AI에_보내지_않는다() {
        retranslator.translateLines(List.of("┌\n가나\n└", "가나"));
        assertEquals(List.of("가나"), sentToAi());
    }

    @Test
    void AI가_바쁘거나_오류면_JOB5030() {
        when(grpc.translateTexts(anyList(), anyLong())).thenThrow(new BrailleGrpcClient.AiBusyException());
        assertEquals(ErrorCode.JOB_RETRANSLATE_FAILED,
                assertThrows(CustomException.class, () -> retranslator.translateLines(List.of("가"))).getErrorCode());

        reset(grpc);
        when(grpc.translateTexts(anyList(), anyLong())).thenThrow(new RuntimeException("UNAVAILABLE"));
        assertEquals(ErrorCode.JOB_RETRANSLATE_FAILED,
                assertThrows(CustomException.class, () -> retranslator.translateLines(List.of("가"))).getErrorCode());
    }

    // ── 조립 ──

    /** 운영 실측: 문단 텍스트 "<!2칸>…" ↔ 점자 "⠀⠀…\n". 표식이 들여쓰기를 만든다(이전 점자에서 이중으로 빌리지 않음) */
    @Test
    void 문단_수정은_표식_들여쓰기와_뒤_줄바꿈을_지킨다() {
        List<String> out = BrailleRetranslator.compose(
                "<!2칸>네 번 먹습니다.", "<!2칸>세 번 먹습니다.", List.of(B + B + "⠠⠝⠀⠘⠾\n"),
                Map.of("<!2칸>네 번 먹습니다.", B + B + "[네 번 먹습니다.]"));
        assertEquals(List.of(B + B + "[네 번 먹습니다.]\n"), out);
    }

    /** 운영 실측 표: 바뀐 행만 새로 점역하고, 틀·안 바뀐 행은 AI 원래 점자. 표식 없는 행은 같은 줄 들여쓰기를 빌린다 */
    @Test
    void 표는_바뀐_행만_재점역하고_나머지는_AI_점자를_그대로_쓴다() {
        String prevText = "┌\n과일  개수\n사과  2\n└";
        String top = "⠿" + "⠛".repeat(30) + "⠿";
        String bottom = "⠿" + "⠶".repeat(30) + "⠿";
        String row1 = B + B + "⠈⠧⠕⠂" + B + B + "⠈⠗⠠⠍";
        List<String> prevBraille = List.of("\n" + top + "\n" + row1 + "\n" + B + B + "⠇⠈⠧⠀⠀⠼⠃\n" + bottom + "\n\n");

        List<String> out = BrailleRetranslator.compose("┌\n과일  개수\n사과  3\n└", prevText, prevBraille,
                Map.of("사과  3", "[사과]" + B + B + "[3]"));

        assertEquals(List.of("\n" + top + "\n" + row1 + "\n" + B + B + "[사과]" + B + B + "[3]\n" + bottom + "\n\n"), out,
                "앞 빈 줄·뒤 빈 줄 2개·틀·첫 행은 그대로, 바뀐 행은 두 칸 들여쓰기 차용");
    }

    @Test
    void 줄을_지우거나_옮겨도_안_바뀐_줄은_AI_점자를_따라간다() {
        List<String> out = BrailleRetranslator.compose("둘\n하나", "하나\n둘", List.of("⠁\n⠃\n"), Map.of());
        assertEquals(List.of("⠃\n⠁\n"), out);
    }

    @Test
    void 새_블록은_뒤에_줄바꿈_하나_틀은_32칸() {
        assertEquals(List.of(B + B + "[새]\n"),
                BrailleRetranslator.compose("<!2칸>새", null, null, Map.of("<!2칸>새", B + B + "[새]")));
        assertEquals(List.of(BrailleRetranslator.FRAME_TOP + "\n[가]\n" + BrailleRetranslator.FRAME_BOTTOM + "\n"),
                BrailleRetranslator.compose("┌\n가\n└", null, null, Map.of("가", "[가]")));
    }

    @Test
    void 텍스트를_비우면_점자도_빈_값() {
        assertEquals(List.of(""), BrailleRetranslator.compose("  ", "가", List.of("⠁\n"), Map.of()));
    }

    @Test
    void 줄_수가_안_맞으면_줄_대응을_믿지_않는다() {
        // 이전 텍스트 1줄 ↔ 점자 2줄(AI가 줄을 나눈 경우) — 재사용·들여쓰기 차용 없이 새로 점역한 값만
        List<String> out = BrailleRetranslator.compose("가", "가", List.of("⠁\n⠃\n"), Map.of("가", "[가]"));
        assertEquals(List.of("[가]\n"), out);
    }

    @Test
    void 아직_점역_안_된_줄_판정은_표_틀을_빼고_본다() {
        assertFalse(BrailleRetranslator.hasUntranslated("┌\n가\n└", Map.of("가", "[가]")));
        assertTrue(BrailleRetranslator.hasUntranslated("가\n나", Map.of("가", "[가]")));
    }
}

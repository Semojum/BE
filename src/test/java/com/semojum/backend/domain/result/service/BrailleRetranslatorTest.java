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

/** 텍스트 → 점자 재점역 — TranslateText(200자 상한·본문만)를 요소 단위로 쓰기 위한 쪼개기·조립·여백 */
class BrailleRetranslatorTest {

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

    @Test
    void 줄마다_따로_점역해_줄바꿈을_지킨다() {
        Map<String, String> out = retranslator.translateBodies(List.of("첫 줄\n둘째 줄"));
        assertEquals("[첫 줄]\n[둘째 줄]", out.get("첫 줄\n둘째 줄"));
    }

    @Test
    void 이백자를_넘는_줄은_띄어쓰기에서_끊고_점자_빈칸으로_잇는다() {
        String word = "가".repeat(99);
        String line = word + " " + word + " " + word; // 99+1+99+1+99 = 299자
        String out = retranslator.translateBodies(List.of(line)).get(line);

        List<String> sent = sentToAi();
        assertTrue(sent.stream().allMatch(s -> s.length() <= BrailleRetranslator.MAX_CHARS), "모든 조각 ≤ 200자");
        assertEquals(List.of(word + " " + word, word), sent);
        assertEquals("[" + word + " " + word + "]" + BrailleRetranslator.BRAILLE_SPACE + "[" + word + "]", out);
    }

    @Test
    void 띄어쓰기_없는_긴_덩어리는_강제로_자르고_빈칸을_넣지_않는다() {
        String blob = "a".repeat(250);
        String out = retranslator.translateBodies(List.of(blob)).get(blob);

        assertEquals(List.of("a".repeat(200), "a".repeat(50)), sentToAi());
        assertEquals("[" + "a".repeat(200) + "][" + "a".repeat(50) + "]", out, "한 낱말 안이라 이음 빈칸 없음");
    }

    @Test
    void 점역자주_마커는_점자의_점역자주_표로_바꾼다() {
        String text = "<!점역자주>그래프: 인구 변화<!/점역자주>";
        String out = retranslator.translateBodies(List.of(text)).get(text);

        assertEquals(List.of("그래프: 인구 변화"), sentToAi(), "마커는 AI에 보내지 않는다");
        assertEquals("⠠⠄[그래프: 인구 변화]⠠⠄", out);
    }

    @Test
    void 같은_문장은_한_번만_보낸다() {
        retranslator.translateBodies(List.of("가나", "가나\n가나"));
        assertEquals(List.of("가나"), sentToAi());
    }

    @Test
    void AI가_바쁘면_JOB5030() {
        when(grpc.translateTexts(anyList(), anyLong())).thenThrow(new BrailleGrpcClient.AiBusyException());
        CustomException e = assertThrows(CustomException.class, () -> retranslator.translateBodies(List.of("가")));
        assertEquals(ErrorCode.JOB_RETRANSLATE_FAILED, e.getErrorCode());
    }

    @Test
    void AI_오류도_JOB5030() {
        when(grpc.translateTexts(anyList(), anyLong())).thenThrow(new RuntimeException("UNAVAILABLE"));
        CustomException e = assertThrows(CustomException.class, () -> retranslator.translateBodies(List.of("가")));
        assertEquals(ErrorCode.JOB_RETRANSLATE_FAILED, e.getErrorCode());
    }

    @Test
    void 빈_텍스트는_AI를_부르지_않는다() {
        assertEquals("", retranslator.translateBodies(List.of("   ")).get("   "));
        verifyNoInteractions(grpc);
    }

    // ── 여백 ──

    @Test
    void 이전_점자의_앞뒤_여백을_입힌다() {
        assertEquals(List.of("  ⠁⠃\n"), BrailleRetranslator.format("⠁⠃", List.of("  ⠿⠿⠿\n")), "본문 문단");
        assertEquals(List.of("\n⠠⠄⠁⠠⠄\n\n"), BrailleRetranslator.format("⠠⠄⠁⠠⠄", List.of("\n⠠⠄⠿⠠⠄\n\n")), "시각 요소");
        assertEquals(List.of("⠁"), BrailleRetranslator.format("⠁", List.of("⠿")), "여백 없던 블록");
    }

    @Test
    void 이전_점자가_없으면_본문_문단_모양() {
        assertEquals(List.of("  ⠁\n"), BrailleRetranslator.format("⠁", null));
        assertEquals(List.of("  ⠁\n"), BrailleRetranslator.format("⠁", List.of("")));
    }

    @Test
    void 텍스트를_비우면_점자도_빈_값() {
        assertEquals(List.of(""), BrailleRetranslator.format("", List.of("  ⠿\n")));
    }
}

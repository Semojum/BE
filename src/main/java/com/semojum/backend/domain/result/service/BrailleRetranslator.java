package com.semojum.backend.domain.result.service;

import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.grpc.BrailleGrpcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 텍스트 패널 편집 → 같은 id 점자 요소 재점역 (3패널, 2026-09-30).
 *
 * <p>AI {@code TranslateText}(꼬리말용 rule-based 점역)를 빌려 쓴다. 제약과 대응:
 * <ul>
 *   <li><b>200자 상한</b> — 줄 단위로 나누고, 긴 줄은 띄어쓰기에서 끊어 보낸 뒤 점자 빈칸(⠀)으로 잇는다.
 *       한국 점자 약자는 어절을 넘지 않아 띄어쓰기에서 끊어도 결과가 같다</li>
 *   <li><b>조판 정보가 없다</b> — AI 페이지 점역은 들여쓰기·앞뒤 빈 줄을 contents에 넣어 주는데
 *       TranslateText는 본문만 준다. 그래서 <b>이전 점자의 앞뒤 여백 모양을 그대로 입힌다</b>
 *       ({@link #format}). 새 블록은 AI 본문 문단 모양(두 칸 들여쓰기 + 줄바꿈)을 쓴다</li>
 *   <li><b>점역자주 마커</b> — 텍스트의 {@code <!점역자주>…<!/점역자주>}는 점자의 점역자주 표(⠠⠄ … ⠠⠄)로 바꾼다</li>
 * </ul>
 *
 * <p>AI 호출은 DB 트랜잭션 밖에서 한다({@link PageSaveFacade}) — 슬롯을 기다리는 동안 커넥션을 쥐지 않도록.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrailleRetranslator {

    static final int MAX_CHARS = 200;
    static final String BRAILLE_SPACE = "⠀";
    static final String TN_OPEN = "<!점역자주>";
    static final String TN_CLOSE = "<!/점역자주>";
    static final String TN_BRAILLE = "⠠⠄";
    /** 새 블록의 여백 — AI가 본문 문단에 주는 모양 */
    static final String NEW_BLOCK_LEAD = "  ";
    static final String NEW_BLOCK_TAIL = "\n";

    /** 슬롯 대기 상한 — 사용자가 저장 버튼을 누르고 기다리는 시간이다 */
    private static final long MAX_WAIT_MS = 20_000;

    private final BrailleGrpcClient grpcClient;

    /**
     * 텍스트 본문들 → 점자 본문(여백 없음). 같은 문장은 한 번만 보낸다.
     *
     * @throws CustomException JOB5030 — AI 슬롯을 못 잡았거나 AI 오류. 호출부는 아무것도 저장하지 않는다
     */
    public Map<String, String> translateBodies(Collection<String> texts) {
        Map<String, List<String>> piecesByText = new LinkedHashMap<>();
        Set<String> unique = new LinkedHashSet<>();
        for (String text : texts) {
            if (piecesByText.containsKey(text)) continue;
            List<String> pieces = pieces(text);
            piecesByText.put(text, pieces);
            for (String p : pieces) if (isTranslatable(p)) unique.add(p);
        }

        Map<String, String> brailleByPiece = new LinkedHashMap<>();
        if (!unique.isEmpty()) {
            List<String> ordered = new ArrayList<>(unique);
            List<String> braille;
            try {
                braille = grpcClient.translateTexts(ordered, MAX_WAIT_MS);
            } catch (BrailleGrpcClient.AiBusyException e) {
                log.warn("재점역 실패 — AI 슬롯 대기 {}ms 초과 (문장 {}개)", MAX_WAIT_MS, ordered.size());
                throw new CustomException(ErrorCode.JOB_RETRANSLATE_FAILED);
            } catch (Exception e) {
                log.warn("재점역 실패 — AI 오류: {}", e.getMessage());
                throw new CustomException(ErrorCode.JOB_RETRANSLATE_FAILED);
            }
            for (int i = 0; i < ordered.size(); i++) brailleByPiece.put(ordered.get(i), braille.get(i));
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (var e : piecesByText.entrySet()) out.put(e.getKey(), assemble(e.getValue(), brailleByPiece));
        return out;
    }

    /**
     * 점자 본문에 이전 점자의 앞뒤 여백(공백·줄바꿈)을 입힌다. 이전 점자가 없으면(새 블록) 본문 문단 모양.
     * 내용이 전부 비면 빈 점자 — 사용자가 텍스트를 비운 블록이다.
     */
    static List<String> format(String body, List<String> previousBraille) {
        if (body.isBlank()) return List.of("");
        String prev = previousBraille == null ? "" : String.join("\n", previousBraille);
        String lead;
        String tail;
        if (prev.isBlank()) {
            lead = NEW_BLOCK_LEAD;
            tail = NEW_BLOCK_TAIL;
        } else {
            int start = 0;
            while (start < prev.length() && isPad(prev.charAt(start))) start++;
            int end = prev.length();
            while (end > start && isPad(prev.charAt(end - 1))) end--;
            lead = prev.substring(0, start);
            tail = prev.substring(end);
        }
        return List.of(lead + body + tail);
    }

    // ── 문장 쪼개기 ──────────────────────────────────────────

    // 조각 표식 — 조립할 때 점자로 바꾸지 않고 그대로 넣는 것들
    private static final String LINE_BREAK = "\n";
    private static final String GLUE_SPACE = "\u0000SP";
    private static final String TN_MARK = "\u0000TN";

    /**
     * 텍스트 → 조각 목록. 번역할 문장 조각 사이사이에 줄바꿈·이음 빈칸·점역자주 표식이 끼어 있다.
     * 앞뒤 여백은 버린다 — 점자의 여백은 {@link #format}이 이전 점자에서 가져온다.
     */
    static List<String> pieces(String text) {
        List<String> out = new ArrayList<>();
        String rest = text.strip();
        while (!rest.isEmpty()) {
            int open = rest.indexOf(TN_OPEN);
            int close = open < 0 ? -1 : rest.indexOf(TN_CLOSE, open + TN_OPEN.length());
            if (open < 0 || close < 0) {
                addLines(rest, out);
                break;
            }
            addLines(rest.substring(0, open), out);
            out.add(TN_MARK);
            addLines(rest.substring(open + TN_OPEN.length(), close), out);
            out.add(TN_MARK);
            rest = rest.substring(close + TN_CLOSE.length());
        }
        return out;
    }

    private static void addLines(String segment, List<String> out) {
        if (segment.isEmpty()) return;
        String[] lines = segment.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.add(LINE_BREAK);
            addChunks(lines[i].strip(), out);
        }
    }

    // 200자 이하로 띄어쓰기에서 끊는다. 띄어쓰기 없이 200자를 넘는 덩어리만 강제로 자른다
    // — 강제로 자른 자리는 한 낱말 안이라 이음 빈칸을 넣지 않는다
    private static void addChunks(String line, List<String> out) {
        if (line.isEmpty()) return;
        StringBuilder cur = new StringBuilder();
        boolean glue = false; // 다음 조각 앞이 띄어쓰기 자리인가
        for (String word : line.split("\\s+")) {
            if (word.isEmpty()) continue;
            if (!cur.isEmpty() && cur.length() + 1 + word.length() > MAX_CHARS) {
                add(out, cur.toString(), glue);
                cur.setLength(0);
                glue = true;
            }
            while (word.length() > MAX_CHARS) { // 여기선 cur가 늘 비어 있다(위에서 비웠다)
                add(out, word.substring(0, MAX_CHARS), glue);
                glue = false;
                word = word.substring(MAX_CHARS);
            }
            if (!cur.isEmpty()) cur.append(' ');
            cur.append(word);
        }
        if (!cur.isEmpty()) add(out, cur.toString(), glue);
    }

    private static void add(List<String> out, String piece, boolean glue) {
        if (glue) out.add(GLUE_SPACE);
        out.add(piece);
    }

    private static boolean isTranslatable(String piece) {
        return !piece.equals(LINE_BREAK) && !piece.equals(GLUE_SPACE) && !piece.equals(TN_MARK);
    }

    private static String assemble(List<String> pieces, Map<String, String> brailleByPiece) {
        StringBuilder sb = new StringBuilder();
        for (String p : pieces) {
            switch (p) {
                case LINE_BREAK -> sb.append('\n');
                case GLUE_SPACE -> sb.append(BRAILLE_SPACE);
                case TN_MARK -> sb.append(TN_BRAILLE);
                default -> sb.append(brailleByPiece.getOrDefault(p, ""));
            }
        }
        return sb.toString();
    }

    private static boolean isPad(char c) {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t';
    }
}

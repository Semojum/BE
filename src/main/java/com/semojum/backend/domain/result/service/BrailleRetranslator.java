package com.semojum.backend.domain.result.service;

import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.grpc.BrailleGrpcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 텍스트 패널 편집 → 같은 id 점자 요소 재점역 (3패널, 2026-09-30).
 *
 * <p>AI {@code TranslateText}(꼬리말용 rule-based 점역)를 빌려 쓴다. 이 기능은 <b>한 줄 본문만</b> 점역하고
 * AI 페이지 점역이 텍스트에 남기는 조판 표식을 모른다. 그래서 <b>줄 단위</b>로 다룬다(실측 2026-09-30 운영):
 * <ul>
 *   <li><b>안 바뀐 줄은 AI가 처음 준 점자를 그대로 쓴다</b> — 텍스트 줄과 점자 줄이 1:1로 대응하므로
 *       (표 틀·표 행 포함) 바뀐 줄만 새로 점역한다. 규칙 엔진 차이로 멀쩡한 줄이 달라지지 않게</li>
 *   <li>{@code <!N칸>} 들여쓰기 표식 → 점자 빈칸(⠀) N개. TranslateText에 넣으면 표식을 버린다(실측)</li>
 *   <li>두 칸 이상 공백(표 열 간격) → 같은 수의 점자 빈칸</li>
 *   <li>표식 없는 줄의 들여쓰기는 이전 점자의 같은 줄에서 가져온다(표 행은 표식 없이 두 칸 들여 쓴다)</li>
 *   <li>표 틀 {@code ┌}·{@code └} → 이전 점자의 틀 줄을 재사용, 없으면 32칸 틀(⠿⠛…⠿ / ⠿⠶…⠿)</li>
 *   <li>점역자주 {@code <!점역자주>}·{@code <!/점역자주>} → 점역자주 표 ⠠⠄</li>
 *   <li><b>200자 상한</b> — 띄어쓰기에서 끊어 보내고 점자 빈칸으로 잇는다(약자는 어절을 넘지 않는다)</li>
 *   <li>블록 앞뒤 빈 줄은 이전 점자 모양을 따른다. 새 블록은 뒤에 줄바꿈 하나</li>
 * </ul>
 *
 * <p>AI 호출은 DB 트랜잭션 밖에서 한다({@link PageSaveFacade}) — 슬롯을 기다리는 동안 커넥션을 쥐지 않도록.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrailleRetranslator {

    static final int MAX_CHARS = 200;
    static final char BLANK = '⠀';
    static final String BRAILLE_SPACE = String.valueOf(BLANK);
    static final String TN_BRAILLE = "⠠⠄";
    static final String FRAME_TOP_TEXT = "┌";
    static final String FRAME_BOTTOM_TEXT = "└";
    /** AI 표 틀 모양 (32칸 실측) */
    static final String FRAME_TOP = "⠿" + "⠛".repeat(30) + "⠿";
    static final String FRAME_BOTTOM = "⠿" + "⠶".repeat(30) + "⠿";

    /** 줄 안의 표식 — 들여쓰기·점역자주·그 밖의 조판 표식, 두 칸 이상 공백 */
    private static final Pattern TOKEN = Pattern.compile("<!(\\d+)칸>|<!/?점역자주>|<![^>]*>| {2,}");

    /** 슬롯 대기 상한 — 사용자가 저장 버튼을 누르고 기다리는 시간이다 */
    private static final long MAX_WAIT_MS = 20_000;

    private final BrailleGrpcClient grpcClient;

    /**
     * 텍스트들의 모든 줄 → 점자 줄(표식 반영). 같은 줄은 한 번만 보낸다.
     * 표 틀·빈 줄처럼 점역할 게 없는 줄은 AI에 보내지 않는다.
     *
     * @throws CustomException JOB5030 — AI 슬롯을 못 잡았거나 AI 오류. 호출부는 아무것도 저장하지 않는다
     */
    public Map<String, String> translateLines(Collection<String> texts) {
        Map<String, List<Piece>> piecesByLine = new LinkedHashMap<>();
        Set<String> unique = new LinkedHashSet<>();
        for (String text : texts) {
            for (String line : lines(text)) {
                if (piecesByLine.containsKey(line) || isFrame(line)) continue;
                List<Piece> pieces = pieces(line);
                piecesByLine.put(line, pieces);
                for (Piece p : pieces) if (p.translate()) unique.add(p.value());
            }
        }

        Map<String, String> brailleByChunk = new LinkedHashMap<>();
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
            for (int i = 0; i < ordered.size(); i++) brailleByChunk.put(ordered.get(i), braille.get(i));
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (var e : piecesByLine.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (Piece p : e.getValue()) sb.append(p.translate() ? brailleByChunk.getOrDefault(p.value(), "") : p.value());
            out.put(e.getKey(), sb.toString());
        }
        return out;
    }

    /**
     * 새 텍스트 → 점자 contents.
     *
     * @param prevText    바꾸기 전 텍스트(없으면 새 블록)
     * @param prevBraille 바꾸기 전 점자(없으면 새 블록)
     * @param lineBraille {@link #translateLines} 결과 — 바뀐 줄의 점자
     */
    static List<String> compose(String newText, String prevText, List<String> prevBraille,
                                Map<String, String> lineBraille) {
        if (newText == null || newText.isBlank()) return List.of("");
        String prevB = prevBraille == null ? "" : String.join("\n", prevBraille);

        String lead = "";
        String tail = "\n";
        List<String> prevBLines = List.of();
        if (!prevB.isBlank()) {
            int start = 0;
            while (start < prevB.length() && prevB.charAt(start) == '\n') start++;
            int end = prevB.length();
            while (end > start && prevB.charAt(end - 1) == '\n') end--;
            lead = prevB.substring(0, start);
            tail = prevB.substring(end);
            prevBLines = List.of(prevB.substring(start, end).split("\n", -1));
        }
        List<String> prevTLines = prevText == null || prevText.isBlank() ? List.of() : lines(prevText);
        // 텍스트 줄과 점자 줄이 1:1일 때만 줄 대응을 믿는다(아니면 줄 재사용·들여쓰기 차용을 하지 않는다)
        boolean aligned = !prevTLines.isEmpty() && prevTLines.size() == prevBLines.size();

        List<String> newLines = lines(newText);
        Set<Integer> used = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < newLines.size(); i++) {
            String line = newLines.get(i);
            Integer j = aligned ? match(prevTLines, line, i, used) : null;
            if (j != null) {
                used.add(j);
                out.add(prevBLines.get(j));                 // 안 바뀐 줄 — AI 원래 점자
            } else if (isFrame(line)) {
                out.add(frame(line, prevBLines));
            } else {
                String braille = lineBraille.getOrDefault(line, "");
                if (!startsWithIndent(line) && aligned && i < prevBLines.size()) {
                    braille = leadingBlanks(prevBLines.get(i)) + braille;  // 표식 없는 줄 — 같은 줄 들여쓰기
                }
                out.add(braille);
            }
        }
        return List.of(lead + String.join("\n", out) + tail);
    }

    /** 이 텍스트에 아직 점역 안 된 줄이 있는가 (표 틀은 번역 대상이 아니라 제외) */
    static boolean hasUntranslated(String text, Map<String, String> lineBraille) {
        for (String line : lines(text)) {
            if (!isFrame(line) && !lineBraille.containsKey(line)) return true;
        }
        return false;
    }

    // ── 줄·조각 ──────────────────────────────────────────────

    /** 조각 — translate=true면 AI에 보낼 본문, false면 그대로 붙일 점자 */
    record Piece(String value, boolean translate) { }

    /** 블록 앞뒤 빈 줄을 뺀 줄 목록 */
    static List<String> lines(String text) {
        int start = 0;
        while (start < text.length() && text.charAt(start) == '\n') start++;
        int end = text.length();
        while (end > start && text.charAt(end - 1) == '\n') end--;
        return List.of(text.substring(start, end).split("\n", -1));
    }

    static List<Piece> pieces(String line) {
        List<Piece> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(line);
        int pos = 0;
        while (m.find()) {
            addText(line.substring(pos, m.start()), out);
            String tok = m.group();
            if (m.group(1) != null) {
                out.add(new Piece(BRAILLE_SPACE.repeat(Integer.parseInt(m.group(1))), false));
            } else if (tok.startsWith("<!점역자주") || tok.startsWith("<!/점역자주")) {
                out.add(new Piece(TN_BRAILLE, false));
            } else if (tok.startsWith("<!")) {
                log.info("재점역: 모르는 조판 표식은 버린다 — {}", tok);
            } else {
                out.add(new Piece(BRAILLE_SPACE.repeat(tok.length()), false)); // 두 칸 이상 공백
            }
            pos = m.end();
        }
        addText(line.substring(pos), out);
        return out;
    }

    // 본문 한 토막 — 앞뒤 한 칸 공백은 빈칸으로 남기고, 200자 이하로 띄어쓰기에서 끊는다.
    // 띄어쓰기 없이 200자를 넘는 덩어리만 강제로 자른다(한 낱말 안이라 이음 빈칸 없음)
    private static void addText(String text, List<Piece> out) {
        if (text.isEmpty()) return;
        if (text.startsWith(" ")) out.add(new Piece(BRAILLE_SPACE, false));
        StringBuilder cur = new StringBuilder();
        boolean glue = false;
        for (String word : text.strip().split(" ")) {
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
        if (text.endsWith(" ") && !text.isBlank()) out.add(new Piece(BRAILLE_SPACE, false));
    }

    private static void add(List<Piece> out, String chunk, boolean glue) {
        if (glue) out.add(new Piece(BRAILLE_SPACE, false));
        out.add(new Piece(chunk, true));
    }

    // 같은 자리가 같으면 그 줄, 아니면 아직 안 쓴 같은 줄 중 첫 번째
    private static Integer match(List<String> prevTLines, String line, int i, Set<Integer> used) {
        if (i < prevTLines.size() && !used.contains(i) && prevTLines.get(i).equals(line)) return i;
        for (int j = 0; j < prevTLines.size(); j++) {
            if (!used.contains(j) && prevTLines.get(j).equals(line)) return j;
        }
        return null;
    }

    private static boolean isFrame(String line) {
        return line.equals(FRAME_TOP_TEXT) || line.equals(FRAME_BOTTOM_TEXT);
    }

    private static String frame(String line, List<String> prevBLines) {
        boolean top = line.equals(FRAME_TOP_TEXT);
        String prefix = top ? "⠿⠛" : "⠿⠶";
        for (String b : prevBLines) if (b.startsWith(prefix)) return b;
        return top ? FRAME_TOP : FRAME_BOTTOM;
    }

    private static boolean startsWithIndent(String line) {
        return line.matches("^<!\\d+칸>.*");
    }

    private static String leadingBlanks(String brailleLine) {
        int n = 0;
        while (n < brailleLine.length() && brailleLine.charAt(n) == BLANK) n++;
        return brailleLine.substring(0, n);
    }
}

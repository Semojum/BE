package com.semojum.backend.domain.rule.service;

import com.semojum.backend.domain.rule.BrailleRule;
import com.semojum.backend.domain.rule.BrailleRuleRegistry;
import com.semojum.backend.domain.rule.dto.BrailleRuleDto;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 점자 규정 검색 — 전량 메모리 순회. 239건이라 인덱스가 필요 없다(실측 12~41µs/회).
 *
 * <p>매칭은 rule_id·조문 경로(부·장·절·항)·규정명·본문을 합친 한 칸에 부분일치라
 * <b>어느 요소에 걸려도 결과에 포함</b>된다. 점수는 결과를 거르지 않고 순서만 정한다.
 */
@Service
@RequiredArgsConstructor
public class BrailleRuleSearchService {

    private static final int MAX_QUERY_LENGTH = 100;
    private static final int MAX_SIZE = 100;
    private static final Set<String> PUBLISHER_CODES = Set.of("MCST", "NLD", "NISE");

    // 매치 위치별 가중치 — 실측으로 맞춘 값(2026-09-01). 큰 쪽이 위로 온다
    private static final int SCORE_ID_EXACT = 1000;
    private static final int SCORE_ID_PARTIAL = 300;
    private static final int SCORE_NAME_EXACT = 200;
    private static final int SCORE_NAME_PREFIX = 140;
    private static final int SCORE_NAME_PARTIAL = 100;
    private static final int SCORE_SECTION = 50;
    private static final int SCORE_CONTENTS = 10;

    private final BrailleRuleRegistry registry;

    public BrailleRuleDto.Page search(String q, String publisher, String part, int page, int size) {
        validate(q, page, size);

        List<String> tokens = tokenize(q);
        String publisherCode = normalizePublisher(publisher);

        List<Scored> hits = new ArrayList<>();
        for (BrailleRule rule : registry.all()) {
            if (publisherCode != null && !publisherCode.equals(rule.publisherCode())) continue;
            if (part != null && !part.isBlank() && !part.equals(partOf(rule))) continue;

            if (tokens.isEmpty()) {                       // 검색어 없으면 전체를 원문 순서로
                hits.add(new Scored(rule, 0, null));
                continue;
            }
            Scored scored = score(rule, tokens);
            if (scored != null) hits.add(scored);         // 토큰 하나라도 못 맞으면 탈락(AND)
        }

        // 1순위 점수 내림차순, 2순위 원문 순서
        hits.sort(Comparator.comparingInt((Scored s) -> -s.score).thenComparingInt(s -> s.rule.displayOrder()));

        long total = hits.size();
        // page*size 를 int 로 곱하면 큰 page 에서 넘쳐 음수가 되고 subList 가 터진다 — long 으로 자른다
        int from = (int) Math.min((long) page * size, hits.size());
        int to = (int) Math.min((long) from + size, hits.size());
        List<BrailleRuleDto.Item> items = hits.subList(from, to).stream().map(Scored::toItem).toList();

        return new BrailleRuleDto.Page(items, page, size, total, (int) Math.ceil((double) total / size));
    }

    /**
     * 토큰마다 가장 강한 매치 위치 하나를 점수로 삼아 합산한다.
     * 본문 매치는 앞쪽에 나올수록 조금 더 준다 — 같은 10점끼리 원문 순서로만 갈리는 걸 막는다.
     */
    private Scored score(BrailleRule rule, List<String> tokens) {
        String id = rule.ruleId().toLowerCase(Locale.ROOT);
        String name = rule.ruleName().toLowerCase(Locale.ROOT);
        String section = rule.sectionDisplay().toLowerCase(Locale.ROOT);
        String contents = rule.contents().toLowerCase(Locale.ROOT);

        int total = 0;
        String matchedIn = null;
        int best = -1;

        for (String token : tokens) {
            int score;
            String where;
            if (id.equals(token))            { score = SCORE_ID_EXACT;    where = "ruleId"; }
            else if (id.contains(token))     { score = SCORE_ID_PARTIAL;  where = "ruleId"; }
            else if (name.equals(token))     { score = SCORE_NAME_EXACT;  where = "ruleName"; }
            else if (name.startsWith(token)) { score = SCORE_NAME_PREFIX; where = "ruleName"; }
            else if (name.contains(token))   { score = SCORE_NAME_PARTIAL;where = "ruleName"; }
            else if (section.contains(token)){ score = SCORE_SECTION;     where = "section"; }
            else if (contents.contains(token)) {
                score = SCORE_CONTENTS + Math.max(0, 8 - contents.indexOf(token) / 10);
                where = "contents";
            } else {
                return null;                  // AND — 이 토큰이 어디에도 없으면 결과에서 뺀다
            }
            total += score;
            if (score > best) { best = score; matchedIn = where; }   // 대표 매치 위치 = 가장 강한 것
        }
        return new Scored(rule, total, matchedIn);
    }

    private void validate(String q, int page, int size) {
        if (q != null && q.length() > MAX_QUERY_LENGTH) throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        if (page < 0 || size < 1 || size > MAX_SIZE) throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
    }

    /** 공백으로 쪼개 AND. 한 단어 검색이면 AND·OR 결과가 같고, 여러 단어일 때만 좁힌다 */
    private List<String> tokenize(String q) {
        if (q == null || q.isBlank()) return List.of();
        return java.util.Arrays.stream(q.trim().toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(t -> !t.isBlank()).toList();
    }

    private String normalizePublisher(String publisher) {
        if (publisher == null || publisher.isBlank()) return null;
        String code = publisher.trim().toUpperCase(Locale.ROOT);
        if (!PUBLISHER_CODES.contains(code)) throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        return code;
    }

    /** MCST 편(기본·한글·수학·과학·외국어) — rule_id 중간 세그먼트. 다른 기관은 편이 없다 */
    private String partOf(BrailleRule rule) {
        String[] segments = rule.ruleId().split("-");
        return segments.length == 3 ? segments[1] : null;
    }

    private record Scored(BrailleRule rule, int score, String matchedIn) {
        BrailleRuleDto.Item toItem() {
            return new BrailleRuleDto.Item(
                    rule.ruleId(), rule.publisherCode(), rule.publisher(), rule.source(), rule.version(),
                    rule.sectionDisplay(), rule.ruleName(), rule.contents(), rule.tag(), matchedIn);
        }
    }
}

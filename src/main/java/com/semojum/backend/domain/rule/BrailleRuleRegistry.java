package com.semojum.backend.domain.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * 점자 규정 표(239건 / 118KB)를 기동 시 한 번 읽어 메모리에 상주시킨다.
 *
 * <p>DB 가 아니라 클래스패스 리소스인 이유 — 순수 읽기 전용 참조 데이터이고, AI registry 와
 * rule_id 집합이 어긋나면 에디터 규정 배지가 깨지며, 로컬·개발·운영이 RDS 하나를 공유해
 * DB 에 두면 배포 롤백 후에도 규정만 새 버전으로 남기 때문이다. 폰트(NanumGothic)와 같은 결.
 * 실측: 로드 17ms / 상주 184KB(힙 768MB 의 0.02%) / 검색 1회 12~41µs.
 *
 * <p>운영자가 무중단으로 규정을 편집해야 하거나 규정에 붙는 사용자 데이터(즐겨찾기 등)가
 * 생기면 DB 로 옮긴다 — 그때 바꿀 곳은 이 클래스의 {@link #load()} 하나다.
 */
@Slf4j
@Component
public class BrailleRuleRegistry {

    private static final String RESOURCE = "/rules/braille-rules.json";

    /** 우선순위 P1 MCST > P2 NLD > P3 NISE (registry _meta 의 priority) */
    private static final Map<String, Integer> PUBLISHER_RANK = Map.of("MCST", 1, "NLD", 2, "NISE", 3);

    /** MCST 는 편이 나뉘어 있고 rule_id 중간 세그먼트가 편 이름이다 */
    private static final Map<String, Integer> MCST_PART_RANK =
            Map.of("기본", 1, "한글", 2, "수학", 3, "과학", 4, "외국어", 5);

    /** 위계 순서 — 없는 단계는 JSON 에 키 자체가 없다 */
    private static final List<String> SECTION_KEYS = List.of("Part", "Chapter", "Section", "Paragraph");

    private List<BrailleRule> rules = List.of();
    private Map<String, BrailleRule> byId = Map.of();

    @PostConstruct
    void load() {
        long started = System.nanoTime();
        List<BrailleRule> loaded = new ArrayList<>();

        try (InputStream is = getClass().getResourceAsStream(RESOURCE)) {
            if (is == null) throw new IllegalStateException("점자 규정 파일 없음: " + RESOURCE);
            JsonNode root = new ObjectMapper().readTree(is).get("rules");

            // 1) 원문 순서로 정렬 — 파일 키 순서는 문자열 정렬이라 MCST-한글-1.4.10 이 1.4.8 앞에 온다
            List<String> ids = new ArrayList<>();
            root.fieldNames().forEachRemaining(ids::add);
            ids.sort(Comparator.comparing(BrailleRuleRegistry::orderKey, BrailleRuleRegistry::compareOrderKey));

            // 2) 순서가 정해진 뒤에야 displayOrder 를 매긴다
            for (int i = 0; i < ids.size(); i++) loaded.add(toRule(ids.get(i), root.get(ids.get(i)), i));

        } catch (IOException e) {
            throw new IllegalStateException("점자 규정 파일 파싱 실패: " + RESOURCE, e);
        }

        this.rules = List.copyOf(loaded);
        this.byId = loaded.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(BrailleRule::ruleId, r -> r));
        log.info("점자 규정 {}건 로드 완료 ({}ms)", rules.size(), (System.nanoTime() - started) / 1_000_000);
    }

    private static BrailleRule toRule(String ruleId, JsonNode node, int displayOrder) {
        JsonNode section = node.get("section");
        String part = text(section, "Part");
        String chapter = text(section, "Chapter");
        String sec = text(section, "Section");
        String paragraph = text(section, "Paragraph");

        StringJoiner path = new StringJoiner(" · ");
        for (String key : SECTION_KEYS) {
            String value = text(section, key);
            if (value != null) path.add(value);
        }
        String sectionDisplay = path.toString();

        String ruleName = node.path("rule_name").asText("");
        String contents = node.path("contents").asText("");
        String tag = node.path("default_tag").asText("");

        // 검색은 이 한 칸만 본다 — rule_id·조문 경로·규정명·본문 어디에 걸려도 결과에 포함된다
        String searchBlob = String.join(" ", ruleId, sectionDisplay, ruleName, contents, tag)
                .toLowerCase(Locale.ROOT);

        return new BrailleRule(
                ruleId, publisherCode(ruleId), node.path("publisher").asText(""),
                node.path("source").asText(""), node.path("version").asInt(),
                part, chapter, sec, paragraph, sectionDisplay,
                ruleName, contents, tag.isBlank() ? null : tag,
                displayOrder, searchBlob);
    }

    private static String text(JsonNode section, String key) {
        JsonNode value = section == null ? null : section.get(key);
        return value == null ? null : value.asText();
    }

    static String publisherCode(String ruleId) {
        return ruleId.substring(0, ruleId.indexOf('-'));
    }

    /**
     * 원문 순서 키 = [기관 순위, MCST 편 순위, 조문 번호…].
     * rule_id 는 {@code NLD-1.1.1} · {@code NISE-5.3} · {@code MCST-한글-1.4.10} 세 모양뿐이고
     * 마지막 세그먼트는 항상 점으로 이은 숫자다(239건 전수 확인).
     */
    static List<Integer> orderKey(String ruleId) {
        String[] segments = ruleId.split("-");
        List<Integer> key = new ArrayList<>();
        key.add(PUBLISHER_RANK.getOrDefault(segments[0], 99));
        key.add(segments.length == 3 ? MCST_PART_RANK.getOrDefault(segments[1], 99) : 0);
        for (String number : segments[segments.length - 1].split("\\.")) key.add(Integer.parseInt(number));
        return key;
    }

    /** 길이가 다르면 짧은 쪽이 앞 (제1장 < 제1장 제1절) */
    static int compareOrderKey(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int cmp = Integer.compare(a.get(i), b.get(i));
            if (cmp != 0) return cmp;
        }
        return Integer.compare(a.size(), b.size());
    }

    /** 원문 순서로 정렬된 전체 규정 (불변) */
    public List<BrailleRule> all() {
        return rules;
    }

    public Optional<BrailleRule> findById(String ruleId) {
        return Optional.ofNullable(byId.get(ruleId));
    }
}

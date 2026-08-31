package com.semojum.backend.domain.rule;

import com.semojum.backend.domain.rule.dto.BrailleRuleDto;
import com.semojum.backend.domain.rule.service.BrailleRuleSearchService;
import com.semojum.backend.global.exception.CustomException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 점자 규정 검색 — 실제 규정 파일(239건)을 그대로 읽어 검증한다 */
class BrailleRuleSearchTest {

    static BrailleRuleRegistry registry;
    static BrailleRuleSearchService service;

    @BeforeAll
    static void setUp() {
        registry = new BrailleRuleRegistry();
        registry.load();
        service = new BrailleRuleSearchService(registry);
    }

    private List<BrailleRuleDto.Item> search(String q) {
        return service.search(q, null, null, 0, 100).items();
    }

    @Test
    void 규정_파일이_전부_로드된다() {
        assertEquals(239, registry.all().size());
        assertTrue(registry.all().stream().noneMatch(r -> r.ruleName().isBlank()));
        assertTrue(registry.all().stream().noneMatch(r -> r.sectionDisplay().isBlank()));
    }

    /**
     * 원문 순서의 핵심 — 파일 키가 문자열 정렬이라 MCST-한글-1.4.10 이 1.4.8 <b>앞에</b> 들어 있다.
     * displayOrder 는 숫자로 비교해 8 → 9 → 10 이 되어야 한다.
     */
    @Test
    void 조문_번호는_문자열이_아니라_숫자로_정렬된다() {
        int p8 = order("MCST-한글-1.4.8"), p9 = order("MCST-한글-1.4.9"), p10 = order("MCST-한글-1.4.10");
        assertTrue(p8 < p9 && p9 < p10, "제8항 < 제9항 < 제10항 이어야 하는데 " + p8 + "/" + p9 + "/" + p10);
    }

    /** 우선순위 P1 MCST > P2 NLD > P3 NISE — 같은 점수면 이 순서로 나온다 */
    @Test
    void 기관_우선순위대로_정렬된다() {
        assertTrue(order("MCST-기본-1") < order("NLD-1.1.1"));
        assertTrue(order("NLD-1.1.1") < order("NISE-5.3"));
    }

    /** MCST 편 순서: 기본 → 한글 → 수학 → 과학 → 외국어 */
    @Test
    void MCST_편_순서가_원문과_같다() {
        assertTrue(order("MCST-기본-8") < order("MCST-한글-1.1.1"));
        assertTrue(order("MCST-한글-1.1.1") < order("MCST-수학-1.1"));
        assertTrue(order("MCST-수학-1.1") < order("MCST-외국어-1.1.1"));
    }

    private int order(String ruleId) {
        return registry.findById(ruleId).orElseThrow(() -> new AssertionError("없는 규정: " + ruleId)).displayOrder();
    }

    /** 규정명이 정확히 일치하는 규정이 본문에 스쳐 지나가는 규정보다 위에 온다 */
    @Test
    void 규정명_일치가_본문_일치보다_먼저_나온다() {
        List<BrailleRuleDto.Item> hits = search("약자");

        assertEquals("MCST-한글-2.6.13", hits.get(0).ruleId());
        assertEquals("ruleName", hits.get(0).matchedIn());
        // 본문에만 "약자인 ⠌"이 스치는 이 규정은 뒤로 밀린다
        int strayIdx = indexOf(hits, "MCST-한글-1.2.4");
        assertTrue(strayIdx > 0, "본문 매치는 규정명 매치 뒤여야 한다");
    }

    /** rule_id 를 그대로 붙여넣으면(에디터 규정 배지에서 복사) 그 규정만 나온다 */
    @Test
    void rule_id_정확일치는_단독으로_최상위다() {
        List<BrailleRuleDto.Item> hits = search("MCST-한글-2.6.13");
        assertEquals(1, hits.size());
        assertEquals("MCST-한글-2.6.13", hits.get(0).ruleId());
        assertEquals("ruleId", hits.get(0).matchedIn());
    }

    /** 어느 요소에 걸려도 결과에 포함 — 조문 경로(장·절)만 걸리는 검색어 */
    @Test
    void 조문_경로에만_걸려도_결과에_나온다() {
        List<BrailleRuleDto.Item> hits = search("제6장");
        assertFalse(hits.isEmpty());
        assertTrue(hits.stream().anyMatch(i -> "section".equals(i.matchedIn())));
        assertTrue(hits.stream().allMatch(i -> i.section().contains("제6장")
                || i.ruleId().contains("제6장") || i.contents().contains("제6장")));
    }

    /**
     * 여러 단어는 AND — 하나만 걸려도 통과시키면 "한글 점자 약자"가 208건(전체 239건 중)이 된다.
     * 한 단어일 때는 AND·OR 결과가 같으므로 손해가 없다.
     */
    @Test
    void 여러_단어는_모두_걸린_규정만_남는다() {
        List<BrailleRuleDto.Item> both = search("숫자 소수");
        assertEquals(1, both.size());
        assertEquals("MCST-한글-5.12.48", both.get(0).ruleId());

        assertTrue(search("숫자").size() > 1, "한 단어면 훨씬 많이 나온다");
        assertTrue(search("한글 점자 약자").size() < 20, "세 단어 AND 는 크게 좁혀진다");
    }

    /** 검색어를 안 주면 전체를 원문 순서로 — 목차처럼 훑는 용도 */
    @Test
    void 검색어가_없으면_전체를_원문_순서로_준다() {
        BrailleRuleDto.Page page = service.search(null, null, null, 0, 100);
        assertEquals(239, page.totalElements());
        assertEquals(100, page.items().size());
        assertEquals(3, page.totalPages());
        assertEquals("MCST-기본-1", page.items().get(0).ruleId());
    }

    @Test
    void 기관과_편으로_좁힐_수_있다() {
        BrailleRuleDto.Page nld = service.search("표", "NLD", null, 0, 100);
        assertFalse(nld.items().isEmpty());
        assertTrue(nld.items().stream().allMatch(i -> "NLD".equals(i.publisherCode())));

        BrailleRuleDto.Page hangul = service.search(null, "mcst", "한글", 0, 100);   // 소문자도 허용
        assertFalse(hangul.items().isEmpty());
        assertTrue(hangul.items().stream().allMatch(i -> i.ruleId().startsWith("MCST-한글-")));
    }

    @Test
    void 페이지네이션이_이어진다() {
        BrailleRuleDto.Page first = service.search(null, null, null, 0, 10);
        BrailleRuleDto.Page second = service.search(null, null, null, 1, 10);

        assertEquals(239, first.totalElements());
        assertEquals(24, first.totalPages());
        assertEquals(10, second.items().size());
        assertNotEquals(first.items().get(0).ruleId(), second.items().get(0).ruleId());

        BrailleRuleDto.Page beyond = service.search(null, null, null, 99, 10);
        assertTrue(beyond.items().isEmpty(), "범위 밖 페이지는 빈 목록(에러 아님)");

        // page*size 가 int 범위를 넘겨도 터지지 않아야 한다 (넘치면 음수가 되어 subList 가 예외)
        BrailleRuleDto.Page overflow = service.search(null, null, null, Integer.MAX_VALUE, 100);
        assertTrue(overflow.items().isEmpty());
    }

    /** 응답의 section 은 rule_trail.section 과 같은 " · " 합본이어야 에디터에서 같은 규정임을 알아본다 */
    @Test
    void section은_rule_trail과_같은_합본_형식이다() {
        BrailleRuleDto.Item item = search("MCST-한글-5.11.40").get(0);
        assertEquals("한글 점자 · 제5장. 숫자와 기호 · 제11절. 국어 문장 안의 숫자 · 제40항", item.section());
        assertEquals("문화체육관광부", item.publisher());
        assertEquals(2024, item.version());
    }

    @Test
    void 잘못된_요청은_COMMON4000() {
        assertThrows(CustomException.class, () -> service.search("q", "KBU", null, 0, 20), "없는 기관 코드");
        assertThrows(CustomException.class, () -> service.search("q", null, null, -1, 20), "음수 page");
        assertThrows(CustomException.class, () -> service.search("q", null, null, 0, 101), "size 상한 초과");
        assertThrows(CustomException.class, () -> service.search("가".repeat(101), null, null, 0, 20), "검색어 길이 초과");
    }

    /** 없는 단어는 빈 결과 — 에러가 아니다 */
    @Test
    void 결과가_없으면_빈_목록이다() {
        BrailleRuleDto.Page page = service.search("존재하지않는검색어", null, null, 0, 20);
        assertTrue(page.items().isEmpty());
        assertEquals(0, page.totalElements());
        assertEquals(0, page.totalPages());
    }

    private int indexOf(List<BrailleRuleDto.Item> items, String ruleId) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).ruleId().equals(ruleId)) return i;
        return -1;
    }
}

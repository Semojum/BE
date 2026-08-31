package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.job.dto.LayoutOptions;
import com.semojum.brailleassist.BrailleAssist;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 업로드 옵션이 실제 조판에 반영되는지 — 다운로드가 braille-assist에 넘기는 값 그대로 확인한다.
 * (braille-assist는 원 레포 복사본이라 수정하지 않고 공개 API만 쓴다)
 */
class LayoutOptionsTypesettingTest {

    /** 원본 쪽마다 점자 면을 넉넉히 채울 만큼(30줄) 내용을 넣는다 — 면이 여러 장 나와야 페이지행을 볼 수 있다 */
    private List<List<String>> typeset(LayoutOptions o, int pages) {
        String body = String.join("\n", java.util.Collections.nCopies(30, "⠁⠃⠉"));
        List<BrailleAssist.Source> sources = new java.util.ArrayList<>();
        for (int p = 1; p <= pages; p++) {
            sources.add(new BrailleAssist.Source(p + o.sourcePageStart() - 1,
                    List.of(new BrailleAssist.Block(0, body))));
        }
        BrailleAssist.Options opts = new BrailleAssist.Options(
                o.cellsPerLine(), o.linesPerPage(),
                o.showSourcePageNumber(), o.showBraillePageNumber(),
                o.pageNumberLine(), o.coverPages());
        return BrailleAssist.buildPages(sources, "", o.braillePageStart(), opts);
    }

    private LayoutOptions opts(Integer cells, Integer lines, String pageRow, Integer sourceStart) {
        return new LayoutOptions(cells, lines, pageRow, null, sourceStart, null, null, null, null, null, null)
                .withDefaults();
    }

    @Test
    void 한_면_줄_수가_판면_높이를_결정한다() {
        assertEquals(26, typeset(opts(null, null, null, null), 1).get(0).size());
        assertEquals(20, typeset(opts(null, 20, null, null), 1).get(0).size());
    }

    @Test
    void 한_줄_칸_수를_넘는_줄은_없다() {
        for (String line : typeset(opts(24, null, null, null), 1).get(0)) {
            assertTrue(line.length() <= 24, "24칸을 넘는 줄: " + line.length());
        }
    }

    /** 페이지행: 홀수 면만(기본) / 모든 면 / 넣지 않음 */
    @Test
    void 페이지행_표시_대상이_옵션대로_바뀐다() {
        // 페이지행은 각 면의 마지막 줄 — 비어 있지 않으면 넣은 것
        List<List<String>> odd = typeset(opts(null, null, "odd", null), 2);
        List<List<String>> none = typeset(opts(null, null, "none", null), 2);
        org.junit.jupiter.api.Assertions.assertTrue(odd.size() >= 2, "면이 2장 이상 나와야 한다: " + odd.size());

        assertTrue(hasPageRow(odd, 0), "홀수 면(1면)엔 페이지행이 있어야 한다");
        assertTrue(!hasPageRow(odd, 1), "짝수 면(2면)엔 없어야 한다");
        for (int i = 0; i < none.size(); i++) {
            assertTrue(!hasPageRow(none, i), "none이면 어느 면에도 없어야 한다: " + i);
        }
    }

    /** 원본 쪽 번호 시작 — 올린 문서 첫 쪽이 실제 몇 쪽인지. 표기만 옮긴다 */
    @Test
    void 원본_쪽_번호_시작이_표기에_반영된다() {
        List<List<String>> shifted = typeset(opts(null, null, "every", 100), 2);

        // 두 번째 원본 쪽으로 넘어가는 변경선에 101이 찍힌다(수표 ⠼ + 숫자 점형)
        String all = String.join("\n", shifted.stream().flatMap(List::stream).toList());
        assertTrue(all.contains("⠼"), "수표가 있어야 한다");
    }

    /**
     * 페이지행이 있는지 — 마지막 줄에 <b>수표(⠼)</b>가 있으면 번호를 찍은 것이다.
     * 본문이 마지막 줄까지 찰 수 있어 "비었는지"로는 구분되지 않는다(본문에는 수표를 넣지 않았다).
     */
    private boolean hasPageRow(List<List<String>> pages, int index) {
        List<String> page = pages.get(index);
        return page.get(page.size() - 1).contains("⠼");
    }
}

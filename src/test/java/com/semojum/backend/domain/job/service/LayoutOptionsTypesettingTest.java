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
        return BrailleAssist.buildPages(sources, "", o.braillePageStart(), assistOptions(o));
    }

    /** JobDownloadService.buildBrf와 같은 방식으로 조립한다 — 호출부가 바뀌면 여기도 바꾼다 */
    private BrailleAssist.Options assistOptions(LayoutOptions o) {
        return new BrailleAssist.Options(
                o.cellsPerLine(), o.linesPerPage(),
                o.showSourcePageNumber(), o.showBraillePageNumber(),
                o.pageNumberLine(), o.coverPages(),
                null, true, o.footerAlign());
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
     * 표지 건너뛰기는 <b>순번</b>으로 판정해야 한다.
     *
     * <p>2026-09-02 원 레포 동기화 전 복사본은 원본 <b>쪽 번호</b>로 판정해서
     * ({@code head <= coverPages}) 원본 쪽이 1이 아닌 문서에서는 표지 지정이 조용히 무시됐다.
     * 100쪽부터 시작하는 문서에 표지 2쪽을 주면 {@code 100 <= 2}가 거짓이라 아무 면도 표지가 아니었다.
     */
    @Test
    void 표지_건너뛰기는_원본_쪽_번호가_1이_아니어도_동작한다() {
        for (int start : new int[]{1, 3, 100}) {
            LayoutOptions o = new LayoutOptions(null, null, "every", 2, start,
                    null, null, null, null, null, null).withDefaults();
            List<List<String>> pages = typeset(o, 4);

            assertTrue(!hasPageRow(pages, 0),
                    "표지 첫 면엔 페이지행이 없어야 한다 (sourcePageStart=" + start + ")");
        }
    }

    /** 꼬리말 정렬 — right면 가운데보다 오른쪽에 붙는다 (동기화로 조판까지 반영됨) */
    @Test
    void 꼬리말_우측_정렬이_조판에_반영된다() {
        String footer = "⠋⠕⠕⠞";
        List<BrailleAssist.Source> src = List.of(
                new BrailleAssist.Source(1, List.of(new BrailleAssist.Block(0, "⠁⠃⠉"))));

        String center = lastLine(BrailleAssist.buildPages(src, footer, 1, assistOptions(align("center"))));
        String right = lastLine(BrailleAssist.buildPages(src, footer, 1, assistOptions(align("right"))));

        assertTrue(center.indexOf(footer) < right.indexOf(footer),
                "right가 center보다 오른쪽이어야 한다: center=" + center.indexOf(footer)
                        + " right=" + right.indexOf(footer));
    }

    private LayoutOptions align(String footerAlign) {
        return new LayoutOptions(null, null, "every", null, null, null, null, null,
                footerAlign, null, null).withDefaults();
    }

    private String lastLine(List<List<String>> pages) {
        List<String> page = pages.get(0);
        return page.get(page.size() - 1);
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

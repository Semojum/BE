package com.semojum.backend.domain.job.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * mode b 입력의 쪽 분리 (FE 요청 B-1).
 *
 * <p>mode a 결과(.txt)는 원본 쪽 사이에 하이픈 40개 줄을 넣는다 — "점역으로 보내기"를 전제한
 * 포맷이다. 종전엔 mode b가 이 표식을 못 알아보고 무조건 30줄로 잘라, 하이픈이 본문으로 점역되고
 * 페이지행의 원본 쪽 번호가 실제 원문과 어긋났다.
 */
class ModeBPageSplitTest {

    private static final String SEP = "-".repeat(40);

    private String lines(int n, String prefix) {
        return IntStream.rangeClosed(1, n).mapToObj(i -> prefix + i).collect(Collectors.joining("\n"));
    }

    // ── 구분선이 있는 경우 (mode a → mode b 이관) ──────────────────

    @Test
    void 구분선이_있으면_그_경계로_자른다() {
        String txt = "1쪽 본문\n둘째 줄\n" + SEP + "\n2쪽 본문\n" + SEP + "\n3쪽 본문";

        List<String> pages = JobService.splitIntoPages(txt);

        assertEquals(3, pages.size(), "30줄이 아니라 구분선 개수+1로 나뉜다");
        assertEquals("1쪽 본문\n둘째 줄", pages.get(0));
        assertEquals("2쪽 본문", pages.get(1));
        assertEquals("3쪽 본문", pages.get(2));
    }

    /** 하이픈 40개가 본문으로 점역되던 문제 — 경계로만 쓰고 버린다 */
    @Test
    void 구분선_자체는_본문에_남지_않는다() {
        List<String> pages = JobService.splitIntoPages("가\n" + SEP + "\n나");

        assertTrue(pages.stream().noneMatch(p -> p.contains(SEP)), "어느 쪽에도 하이픈 줄이 없어야 한다");
    }

    /** 쪽 하나가 30줄을 넘어도 구분선이 있으면 쪼개지 않는다 — 원문 쪽이 곧 한 쪽이다 */
    @Test
    void 구분선이_있으면_30줄을_넘겨도_안_쪼갠다() {
        String txt = lines(50, "가") + "\n" + SEP + "\n" + lines(45, "나");

        List<String> pages = JobService.splitIntoPages(txt);

        assertEquals(2, pages.size());
        assertEquals(50, pages.get(0).split("\n").length);
        assertEquals(45, pages.get(1).split("\n").length);
    }

    /** 구분선이 연달아 있거나 끝에 붙어도 빈 쪽을 만들지 않는다 */
    @Test
    void 빈_쪽은_만들지_않는다() {
        assertEquals(2, JobService.splitIntoPages("가\n" + SEP + "\n" + SEP + "\n나").size());
        assertEquals(1, JobService.splitIntoPages("가\n" + SEP).size(), "끝에 붙은 구분선");
        assertEquals(1, JobService.splitIntoPages(SEP + "\n가").size(), "앞에 붙은 구분선");
    }

    // ── 구분선이 없는 보통 TXT (기존 동작 유지) ────────────────────

    @Test
    void 구분선이_없으면_30줄씩_자른다() {
        assertEquals(1, JobService.splitIntoPages(lines(30, "줄")).size());
        assertEquals(2, JobService.splitIntoPages(lines(31, "줄")).size());
        assertEquals(3, JobService.splitIntoPages(lines(61, "줄")).size());
    }

    @Test
    void 짧은_글도_한_쪽은_나온다() {
        assertEquals(1, JobService.splitIntoPages("한 줄").size());
        assertEquals(1, JobService.splitIntoPages("").size());
    }

    /**
     * 길이가 다른 하이픈 줄은 구분선이 아니다 — <b>정확히 40개</b>만 본다(유저 확정 2026-09-03).
     * 본문에 들어 있는 밑줄·구분 장식을 경계로 오인하지 않게.
     */
    @Test
    void 하이픈_개수가_다르면_구분선이_아니다() {
        for (int n : new int[]{39, 41, 3, 80}) {
            String txt = lines(5, "가") + "\n" + "-".repeat(n) + "\n" + lines(5, "나");
            List<String> pages = JobService.splitIntoPages(txt);

            assertEquals(1, pages.size(), "하이픈 " + n + "개는 경계가 아니다");
            assertTrue(pages.get(0).contains("-".repeat(n)), "본문에 그대로 남는다");
        }
    }

    /** 앞뒤에 다른 글자가 붙은 줄도 아니다 */
    @Test
    void 하이픈_줄에_다른_글자가_섞이면_구분선이_아니다() {
        assertEquals(1, JobService.splitIntoPages("가\n" + SEP + "끝\n나").size());
        assertEquals(1, JobService.splitIntoPages("가\n " + SEP + "\n나").size());
    }
}

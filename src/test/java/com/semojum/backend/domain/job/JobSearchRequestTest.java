package com.semojum.backend.domain.job;

import com.semojum.backend.domain.job.dto.JobSearchCondition;
import com.semojum.backend.domain.job.dto.JobSearchRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.ServletRequestDataBinder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// 쿼리스트링 → JobSearchRequest 바인딩을 검증한다.
// 조회 범위(folderId·allScope)는 경로가 정하므로 쿼리에서 바인딩되지 않는다.
class JobSearchRequestTest {

    private JobSearchRequest target(String... kv) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        for (int i = 0; i < kv.length; i += 2) req.addParameter(kv[i], kv[i + 1]);
        JobSearchRequest target = new JobSearchRequest();
        new ServletRequestDataBinder(target).bind(req);
        return target;
    }

    /** 전역 조회(전체보기·검색)에서의 변환 */
    private JobSearchCondition global(String... kv) {
        return target(kv).toCondition(null, true);
    }

    @Test
    void 파라미터가_없으면_최신순_30개가_기본값이다() {
        JobSearchCondition c = global();
        assertNull(c.search());
        assertFalse(c.oldestFirst());
        assertEquals(30, c.normalizedSize());
    }

    @Test
    void 조회_범위는_쿼리가_아니라_호출부가_정한다() {
        UUID folder = UUID.randomUUID();
        // 같은 쿼리라도 호출부가 넘긴 범위대로 만들어진다
        JobSearchCondition scoped = target().toCondition(folder, false);
        assertEquals(folder, scoped.folderId());
        assertFalse(scoped.allScope());

        JobSearchCondition all = target().toCondition(null, true);
        assertNull(all.folderId());
        assertTrue(all.allScope());
    }

    @Test
    void 쿼리로_folderId나_scope를_보내도_범위가_바뀌지_않는다() {
        UUID attacker = UUID.randomUUID();
        JobSearchCondition c = target("folderId", attacker.toString(), "scope", "all")
                .toCondition(null, false);
        assertNull(c.folderId(), "쿼리의 folderId는 무시된다");
        assertFalse(c.allScope(), "쿼리의 scope는 무시된다");
    }

    @Test
    void sort_oldest가_내부_표현으로_변환된다() {
        assertTrue(global("sort", "oldest").oldestFirst());
        assertTrue(global("sort", "OLDEST").oldestFirst());   // 대소문자 무시
        assertFalse(global("sort", "latest").oldestFirst());
    }

    @Test
    void 복수값_필터가_리스트로_바인딩된다() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addParameter("mode", "a", "b");
        req.addParameter("status", "COMPLETED", "FAILED");
        JobSearchRequest t = new JobSearchRequest();
        new ServletRequestDataBinder(t).bind(req);
        JobSearchCondition c = t.toCondition(null, true);
        assertEquals(List.of("a", "b"), c.modes());
        assertEquals(List.of("COMPLETED", "FAILED"), c.statuses());
    }

    @Test
    void favorite와_search가_그대로_전달된다() {
        JobSearchCondition c = global("favorite", "true", "search", "논문");
        assertEquals(Boolean.TRUE, c.favoriteOnly());
        assertEquals("논문", c.search());
    }

    @Test
    void size는_상한_100으로_잘리고_0이하는_기본값이_된다() {
        assertEquals(100, global("size", "500").normalizedSize());
        assertEquals(30, global("size", "0").normalizedSize());
        assertEquals(15, global("size", "15").normalizedSize());
    }

    @Test
    void 커서는_가공없이_그대로_전달된다() {
        assertEquals("abc123", global("cursor", "abc123").cursor());
    }
}

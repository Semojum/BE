package com.semojum.backend.domain.job;

import com.semojum.backend.domain.job.dto.JobSearchCondition;
import com.semojum.backend.domain.job.dto.JobSearchRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.ServletRequestDataBinder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// 쿼리스트링 → JobSearchRequest 바인딩이 기존 @RequestParam 방식과 동일하게 동작하는지 검증한다.
// (API 계약이 바뀌지 않았음을 보장)
class JobSearchRequestTest {

    private JobSearchCondition bind(String... kv) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        for (int i = 0; i < kv.length; i += 2) req.addParameter(kv[i], kv[i + 1]);
        JobSearchRequest target = new JobSearchRequest();
        new ServletRequestDataBinder(target).bind(req);
        return target.toCondition();
    }

    @Test
    void 파라미터가_없으면_루트_최신순_30개가_기본값이다() {
        JobSearchCondition c = bind();
        assertNull(c.folderId());
        assertFalse(c.allScope());
        assertNull(c.search());
        assertFalse(c.oldestFirst());
        assertEquals(30, c.normalizedSize());
    }

    @Test
    void scope_all과_sort_oldest가_내부_표현으로_변환된다() {
        JobSearchCondition c = bind("scope", "all", "sort", "oldest");
        assertTrue(c.allScope());
        assertTrue(c.oldestFirst());
        // 대소문자 무시
        assertTrue(bind("scope", "ALL", "sort", "OLDEST").allScope());
        assertTrue(bind("scope", "ALL", "sort", "OLDEST").oldestFirst());
    }

    @Test
    void 복수값_필터가_리스트로_바인딩된다() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addParameter("mode", "a", "b");
        req.addParameter("status", "COMPLETED", "FAILED");
        JobSearchRequest target = new JobSearchRequest();
        new ServletRequestDataBinder(target).bind(req);
        JobSearchCondition c = target.toCondition();
        assertEquals(List.of("a", "b"), c.modes());
        assertEquals(List.of("COMPLETED", "FAILED"), c.statuses());
    }

    @Test
    void folderId와_favorite_search가_그대로_전달된다() {
        UUID id = UUID.randomUUID();
        JobSearchCondition c = bind("folderId", id.toString(), "favorite", "true", "search", "논문");
        assertEquals(id, c.folderId());
        assertEquals(Boolean.TRUE, c.favoriteOnly());
        assertEquals("논문", c.search());
    }

    @Test
    void size는_상한_100으로_잘리고_0이하는_기본값이_된다() {
        assertEquals(100, bind("size", "500").normalizedSize());
        assertEquals(30, bind("size", "0").normalizedSize());
        assertEquals(15, bind("size", "15").normalizedSize());
    }

    @Test
    void 커서는_가공없이_그대로_전달된다() {
        assertEquals("abc123", bind("cursor", "abc123").cursor());
    }
}

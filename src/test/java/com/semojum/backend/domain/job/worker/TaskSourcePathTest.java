package com.semojum.backend.domain.job.worker;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 태스크 JSON의 원본 조각 경로 필드 — {@code gcsPath} → {@code sourcePath} 개명 (2026-09-11).
 *
 * <p>큐가 Redis에 있어 배포로 비워지지 않는다. 이름을 바꾸는 배포 순간 큐에 남아 있던 구 형식
 * 태스크가 워커에 도달하므로, 한 릴리스만 두 이름을 모두 읽어야 그 쪽들이 실패하지 않는다.
 */
class TaskSourcePathTest {

    private Map<String, Object> task(String key, String value) {
        Map<String, Object> m = new HashMap<>();
        m.put("jobId", "job-1");
        m.put("pageNo", 1);
        if (key != null) m.put(key, value);
        return m;
    }

    @Test
    void 새_이름을_읽는다() {
        assertEquals("job-1/pages/page-1.pdf",
                PageWorker.sourcePathOf(task("sourcePath", "job-1/pages/page-1.pdf")));
    }

    /** 개명 배포 순간 큐에 남아 있던 태스크 — 이게 없으면 그 쪽들이 경로를 못 찾아 실패한다 */
    @Test
    void 구_이름도_읽는다() {
        assertEquals("job-1/pages/page-1.pdf",
                PageWorker.sourcePathOf(task("gcsPath", "job-1/pages/page-1.pdf")));
    }

    /** 둘 다 있으면 새 이름이 이긴다 — 구 이름은 폴백일 뿐이다 */
    @Test
    void 둘_다_있으면_새_이름이_이긴다() {
        Map<String, Object> both = task("sourcePath", "새경로");
        both.put("gcsPath", "구경로");

        assertEquals("새경로", PageWorker.sourcePathOf(both));
    }

    /** 경로가 아예 없으면 null — 호출부의 재시도 경로가 받는다(무로그 증발 방지) */
    @Test
    void 경로가_없으면_null이다() {
        assertNull(PageWorker.sourcePathOf(task(null, null)));
    }
}

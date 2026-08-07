package com.semojum.backend.domain.user;

import com.semojum.backend.domain.job.dto.JobSearchCondition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 최근 작업 조회가 만드는 조회 조건을 고정한다.
 *
 * <p>이 화면(S1 스트립·S9 전체보기)은 폴더를 그리지 않고 정렬도 최신순으로 고정이라,
 * 컨트롤러가 넘기는 조건이 의도대로인지가 계약의 전부다.
 */
class RecentJobsTest {

    /** UserController.getRecentJobs가 만드는 조건과 동일하게 구성한다. */
    private JobSearchCondition recentCondition(String cursor, int size) {
        return new JobSearchCondition(null, true, null, null, null, null, false, cursor, size);
    }

    @Test
    void 항상_전역이며_최신순이다() {
        JobSearchCondition c = recentCondition(null, 30);

        assertNull(c.folderId(), "폴더 범위를 타지 않는다");
        assertTrue(c.allScope(), "위치 무관 전역이다");
        assertFalse(c.oldestFirst(), "최신순 고정");
    }

    @Test
    void 필터는_받지_않는다() {
        JobSearchCondition c = recentCondition(null, 30);

        assertNull(c.search());
        assertNull(c.statuses());
        assertNull(c.modes());
        assertNull(c.favoriteOnly());
    }

    @Test
    void 커서와_크기는_그대로_전달된다() {
        JobSearchCondition c = recentCondition("abc123", 5);

        assertEquals("abc123", c.cursor());
        assertEquals(5, c.normalizedSize(), "스트립은 size=5로 부른다");
    }

    @Test
    void 크기는_상한_100으로_잘린다() {
        assertEquals(100, recentCondition(null, 500).normalizedSize());
        assertEquals(30, recentCondition(null, 0).normalizedSize(), "0 이하는 기본값");
    }

    @Test
    void 진행_중_작업도_포함한다() {
        // 상태 필터가 없으므로 변환 중인 작업도 목록에 남는다("변환 중" 카드 표시용)
        JobSearchCondition c = recentCondition(null, 30);
        assertNull(c.statuses());
        assertNotEquals(List.of("COMPLETED"), c.statuses());
    }
}

package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.scheduler.JobDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 폴링 API(`GET /api/jobs/{id}/status`)의 종료 판정 — DB와 같은 답을 내야 한다.
 *
 * <p>판정 규칙의 정본은 {@code ResultService.evaluateJobTermination}이다:
 * 전 쪽이 terminal일 때 성공(COMPLETED·NEEDS_REVIEW)이 0건이면 FAILED, 1건 이상이면 COMPLETED.
 * 예전엔 BLOCKED를 성공과 함께 세어 취소로 전 쪽이 BLOCKED된 작업을 COMPLETED라고 답했고,
 * 같은 작업을 DB로 읽는 마이페이지·운영자 화면은 FAILED라 서로 어긋났다(2026-09-02 실측).
 */
class JobStatusOverallTest {

    JobService jobService;
    HashOperations<String, Object, Object> hashOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RedisTemplate<String, String> redisTemplate = Mockito.mock(RedisTemplate.class);
        hashOps = Mockito.mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn((HashOperations) hashOps);

        jobService = Mockito.mock(JobService.class, Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(jobService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(jobService, "jobDispatcher", Mockito.mock(JobDispatcher.class));
    }

    /** Redis Hash 흉내 — total_pages + page:N 상태들 */
    private JobResponseDto.Status statusOf(String... pageStates) {
        Map<Object, Object> data = new LinkedHashMap<>();
        data.put("total_pages", String.valueOf(pageStates.length));
        for (int i = 0; i < pageStates.length; i++) data.put("page:" + (i + 1), pageStates[i]);
        when(hashOps.entries(anyString())).thenReturn(data);
        return jobService.getJobStatus("job-1");
    }

    /**
     * 회귀 방지 본체 — 취소로 전 쪽이 BLOCKED된 작업.
     * DB(ResultService)는 FAILED로 쓰므로 폴링도 FAILED여야 한다.
     */
    @Test
    void 전_쪽이_BLOCKED면_FAILED다() {
        JobResponseDto.Status s = statusOf("BLOCKED", "BLOCKED", "BLOCKED");

        assertEquals("FAILED", s.overallStatus(), "성공 0건이면 FAILED (예전엔 COMPLETED였다)");
        assertEquals(3, s.completedPages(), "끝난 쪽 수는 BLOCKED를 포함해야 진행률이 100%에 닿는다");
    }

    /** 한 쪽이라도 결과가 나왔으면 부분 성공 = COMPLETED (205쪽 실측 사례: BLOCKED 11쪽 섞임) */
    @Test
    void 일부만_BLOCKED면_COMPLETED다() {
        assertEquals("COMPLETED", statusOf("NEEDS_REVIEW", "BLOCKED", "COMPLETED").overallStatus());
        assertEquals("COMPLETED", statusOf("BLOCKED", "BLOCKED", "COMPLETED").overallStatus(), "성공 1건이면 충분");
    }

    /** NEEDS_REVIEW는 결과가 나온 쪽이다 — 검토가 필요할 뿐 실패가 아니다 */
    @Test
    void NEEDS_REVIEW만_있어도_COMPLETED다() {
        assertEquals("COMPLETED", statusOf("NEEDS_REVIEW", "NEEDS_REVIEW").overallStatus());
    }

    /**
     * 진행률 회귀 방지 — completedPages에서 BLOCKED를 빼면 실패가 섞인 작업의 분자가
     * total에 영원히 못 닿아 화면이 "진행 중"에 멈춘다.
     */
    @Test
    void completedPages는_BLOCKED를_포함한다() {
        JobResponseDto.Status s = statusOf("COMPLETED", "BLOCKED", "NEEDS_REVIEW", "BLOCKED");

        assertEquals(4, s.completedPages());
        assertEquals(s.totalPages(), s.completedPages(), "끝났으면 분자 == 분모");
        assertEquals(0, s.pendingPages());
        assertEquals(0, s.runningPages());
    }

    /** 진행 중·미착수 판정은 그대로 */
    @Test
    void 진행_중과_미착수는_종전과_같다() {
        assertEquals("IN_PROGRESS", statusOf("COMPLETED", "RUNNING", "PENDING").overallStatus());
        assertEquals("IN_PROGRESS", statusOf("BLOCKED", "PENDING").overallStatus(), "실패 쪽이 생겨도 남았으면 진행 중");
        assertEquals("PENDING", statusOf("PENDING", "PENDING").overallStatus());
    }
}

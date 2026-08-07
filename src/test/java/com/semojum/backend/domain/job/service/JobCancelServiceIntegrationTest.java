package com.semojum.backend.domain.job.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 변환 취소 검증 — 실제 Redis(Testcontainers) + 모의 리포지토리.
 * 핵심 규칙: 완료된 마지막 페이지(K)까지만 남긴다 / 인플라이트는 마무리를 기다린다 /
 * 중간에 낀 미변환 페이지는 번호 구멍을 막기 위해 BLOCKED / 완료 0건이면 FAILED.
 */
class JobCancelServiceIntegrationTest {

    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate redisTemplate;

    JobRepository jobRepository;
    PageRepository pageRepository;
    JobCancelService service;

    static final String JOB = "job-cancel-test";
    static final String USER = UUID.randomUUID().toString();
    Job job;
    List<Page> pages;

    @BeforeAll
    static void startRedis() {
        redis.start();
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        redis.stop();
    }

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        jobRepository = Mockito.mock(JobRepository.class);
        pageRepository = Mockito.mock(PageRepository.class);
        service = new JobCancelService(jobRepository, pageRepository, redisTemplate, new ObjectMapper());
    }

    /** pages[i] 상태를 지정해 잡을 구성한다. 상태는 DB(Page.status)와 Redis 해시 양쪽에 넣는다 */
    private void givenJob(String... pageStatuses) {
        job = Job.builder().id(JOB).mode("a").totalPages(pageStatuses.length).originalFileName("f.pdf").build();
        job.updateStatus("IN_PROGRESS");
        pages = new ArrayList<>();
        redisTemplate.opsForHash().put(hashKey(), "total_pages", String.valueOf(pageStatuses.length));
        for (int i = 0; i < pageStatuses.length; i++) {
            int pageNo = i + 1;
            Page page = Page.builder().job(job).pageNo(pageNo).pdfPath("s3/page-" + pageNo + ".pdf").build();
            if (!"PENDING".equals(pageStatuses[i]) && !"RUNNING".equals(pageStatuses[i])) {
                page.updateStatus(pageStatuses[i]); // DB에는 terminal 상태만 기록됨 (RUNNING은 Redis 전용)
            }
            pages.add(page);
            redisTemplate.opsForHash().put(hashKey(), "page:" + pageNo, pageStatuses[i]);
        }
        when(jobRepository.findByIdAndUserId(eq(JOB), any())).thenReturn(Optional.of(job));
        when(jobRepository.findById(JOB)).thenReturn(Optional.of(job));
        when(pageRepository.findByJob(job)).thenAnswer(inv -> new ArrayList<>(pages));
        when(pageRepository.findByJobAndPageNo(eq(job), anyInt())).thenAnswer(inv -> {
            int no = inv.getArgument(1);
            return pages.stream().filter(p -> p.getPageNo() == no).findFirst();
        });
        // delete를 실제 목록에서 제거로 흉내 — findByJob 재호출 시 반영되도록
        Mockito.doAnswer(inv -> { pages.remove((Page) inv.getArgument(0)); return null; })
                .when(pageRepository).delete(any(Page.class));
    }

    private String hashKey() { return "job:" + JOB + ":pages"; }
    private String queueKey() { return "queue:job:" + JOB; }

    private void enqueue(int... pageNos) {
        for (int no : pageNos) {
            redisTemplate.opsForList().rightPush(queueKey(),
                    "{\"jobId\":\"" + JOB + "\",\"pageNo\":" + no + ",\"mode\":\"a\",\"totalPages\":" + job.getTotalPages() + "}");
        }
    }

    /** 대기 페이지만 남은 취소 — 즉시 확정: 완료 범위 뒤쪽은 삭제되고 total_pages가 줄어든다 */
    @Test
    void 큐_대기_페이지는_회수되고_뒤쪽은_잘려나간다() {
        givenJob("COMPLETED", "NEEDS_REVIEW", "PENDING", "PENDING", "PENDING");
        enqueue(3, 4, 5);

        Map<String, Object> result = service.cancel(USER, JOB);

        assertEquals(true, result.get("canceled"));
        assertEquals("COMPLETED", result.get("status"), "성공 페이지가 있으므로 부분 완료");
        assertEquals(2, result.get("totalPages"));
        assertEquals(0L, redisTemplate.opsForList().size(queueKey()), "큐 배수됨");
        verify(jobRepository).updateTotalPages(JOB, 2);
        verify(jobRepository).finishJob(eq(JOB), eq("COMPLETED"), eq("{}"));
        assertEquals(2, pages.size(), "3·4·5페이지 행 삭제");
        assertEquals("2", redisTemplate.opsForHash().get(hashKey(), "total_pages"));
        assertNull(redisTemplate.opsForHash().get(hashKey(), "page:4"), "잘린 페이지 해시 필드 제거");
        assertNotNull(job.getCanceledAt(), "취소 시각 기록");
        assertEquals(5, job.getOriginalTotalPages(), "잘리기 전 원래 규모 보존");
    }

    /** 인플라이트(RUNNING)가 있으면 확정을 보류하고, 그 페이지 완료 후 워커의 tryFinalize가 마무리한다 */
    @Test
    void 인플라이트는_마무리를_기다렸다가_확정한다() {
        givenJob("COMPLETED", "RUNNING", "PENDING");
        enqueue(3);

        Map<String, Object> result = service.cancel(USER, JOB);

        assertEquals("IN_PROGRESS", result.get("status"), "인플라이트 마무리 대기");
        assertEquals(1L, result.get("inFlightPages"));
        verify(jobRepository, never()).finishJob(anyString(), anyString(), anyString());

        // 워커가 페이지 2 변환을 마침 (save()가 DB에 쓰고 해시를 갱신하는 것을 흉내)
        pages.get(1).updateStatus("COMPLETED");
        redisTemplate.opsForHash().put(hashKey(), "page:2", "COMPLETED");
        JobCancelService.FinalizeResult finalize = service.tryFinalize(JOB);

        assertNotNull(finalize, "마지막 인플라이트 종료 후 확정");
        assertEquals("COMPLETED", finalize.status());
        assertEquals(2, finalize.totalPages(), "인플라이트로 완료된 2페이지까지 보존");
        verify(jobRepository).finishJob(eq(JOB), eq("COMPLETED"), eq("{}"));
    }

    /** 한 페이지도 변환하지 못한 취소 — 전부 BLOCKED로 남기고 FAILED (총 페이지 수 유지) */
    @Test
    void 완료가_없으면_전부_BLOCKED_FAILED() {
        givenJob("PENDING", "PENDING", "PENDING");
        enqueue(1, 2, 3);

        Map<String, Object> result = service.cancel(USER, JOB);

        assertEquals("FAILED", result.get("status"));
        assertEquals(3, result.get("totalPages"), "잘라낼 완료 범위가 없어 총 수 유지");
        verify(jobRepository, never()).updateTotalPages(anyString(), anyInt());
        verify(jobRepository).finishJob(eq(JOB), eq("FAILED"), eq("{1,2,3}"));
        assertEquals("BLOCKED", redisTemplate.opsForHash().get(hashKey(), "page:2"));
        assertTrue(pages.stream().allMatch(p -> "BLOCKED".equals(p.getStatus())));
    }

    /** 완료 범위 중간에 낀 미변환 페이지(재시도 대기)는 페이지 번호 구멍을 막기 위해 BLOCKED로 남긴다 */
    @Test
    void 중간에_낀_미변환_페이지는_BLOCKED로_남긴다() {
        givenJob("COMPLETED", "PENDING", "COMPLETED", "PENDING");
        enqueue(2, 4); // 2는 재시도 대기로 큐에 재등록돼 있던 상황

        Map<String, Object> result = service.cancel(USER, JOB);

        assertEquals("COMPLETED", result.get("status"));
        assertEquals(3, result.get("totalPages"), "완료된 마지막 페이지(3)까지 보존");
        assertEquals("BLOCKED", pages.get(1).getStatus(), "2페이지는 구멍 방지 BLOCKED");
        assertEquals(3, pages.size(), "4페이지만 삭제");
        verify(jobRepository).finishJob(eq(JOB), eq("COMPLETED"), eq("{2}"));
    }

    /** 이미 끝난 작업 취소는 멱등 — 아무것도 바꾸지 않고 현재 상태만 알려준다 */
    @Test
    void 이미_종료된_작업은_멱등() {
        givenJob("COMPLETED", "COMPLETED");
        job.updateStatus("COMPLETED");

        Map<String, Object> result = service.cancel(USER, JOB);

        assertEquals(false, result.get("canceled"));
        assertEquals("COMPLETED", result.get("status"));
        assertFalse(service.isCanceled(JOB), "취소 플래그도 세우지 않음");
        verify(jobRepository, never()).finishJob(anyString(), anyString(), anyString());
    }

    /** 플래그 세팅 직전에 워커가 집어간 태스크 — 워커의 cancelPage 폐기 경로로 수렴한다 */
    @Test
    void 워커가_집어간_태스크는_cancelPage로_수렴한다() {
        givenJob("COMPLETED", "PENDING"); // 페이지 2 태스크는 워커 손에 (큐에 없음, 해시 PENDING)

        Map<String, Object> result = service.cancel(USER, JOB);
        assertEquals("IN_PROGRESS", result.get("status"), "해시 PENDING(찰나의 경합)은 확정 보류");

        // 워커: poll 직후 취소 플래그 발견 → 폐기
        assertTrue(service.isCanceled(JOB));
        service.cancelPage(JOB, 2);

        verify(jobRepository).finishJob(eq(JOB), eq("COMPLETED"), eq("{}"));
        verify(jobRepository).updateTotalPages(JOB, 1);
        assertEquals(1, pages.size(), "gRPC 미전송 페이지는 삭제");
    }
}

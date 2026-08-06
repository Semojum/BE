package com.semojum.backend.domain.job.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 공정 스케줄러(JobDispatcher) 동작 검증 — 실제 Redis(Testcontainers) 사용.
 * 설계 문서(노션 "[V3] 작업 스케줄링")의 규칙을 그대로 시나리오로 옮겼다.
 */
class JobDispatcherIntegrationTest {

    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate redisTemplate;

    JobRepository jobRepository;
    JobDispatcher dispatcher;

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
        dispatcher = new JobDispatcher(redisTemplate, jobRepository, new ObjectMapper());
    }

    private static String task(String jobId, int pageNo) {
        return "{\"jobId\":\"" + jobId + "\",\"pageNo\":" + pageNo + ",\"userId\":\"u\"}";
    }

    private static List<String> tasks(String jobId, int pages) {
        List<String> list = new ArrayList<>();
        for (int p = 1; p <= pages; p++) list.add(task(jobId, p));
        return list;
    }

    /** poll 결과에서 jobId만 추출 */
    private String polledJobId() {
        String t = dispatcher.poll();
        if (t == null) return null;
        return t.replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void 유저간_공평_그리고_유저안_작업간_라운드로빈() {
        // A는 작업 3개, B는 작업 1개 — 문서의 시뮬레이션 시나리오 그대로
        dispatcher.enqueueJob("userA", "a1", tasks("a1", 2));
        dispatcher.enqueueJob("userA", "a2", tasks("a2", 2));
        dispatcher.enqueueJob("userA", "a3", tasks("a3", 2));
        dispatcher.enqueueJob("userB", "b1", tasks("b1", 4));

        List<String> order = new ArrayList<>();
        for (int i = 0; i < 8; i++) order.add(polledJobId());

        // A·B 번갈아(유저 공평) + A 몫 안에서는 a1→a2→a3 순환(작업 공평)
        assertEquals(List.of("a1", "b1", "a2", "b1", "a3", "b1", "a1", "b1"), order);
    }

    @Test
    void FG_4회_BG_1회_가중_배분() {
        dispatcher.enqueueJob("userA", "fgJob", tasks("fgJob", 10));
        dispatcher.enqueueJob("userC", "bgJob", tasks("bgJob", 10));
        // C는 앱 종료 상태로 만든다 (FG 리스 제거 = TTL 만료와 동일)
        redisTemplate.delete(JobDispatcher.fgLeaseKey("bgJob"));

        List<String> order = new ArrayList<>();
        for (int i = 0; i < 10; i++) order.add(polledJobId());

        // 5회 주기마다 5번째(인덱스 4, 9)만 BG
        assertEquals(List.of("fgJob", "fgJob", "fgJob", "fgJob", "bgJob",
                             "fgJob", "fgJob", "fgJob", "fgJob", "bgJob"), order);
    }

    @Test
    void FG가_없으면_BG가_슬롯_전부_사용() {
        dispatcher.enqueueJob("userC", "bgOnly", tasks("bgOnly", 5));
        redisTemplate.delete(JobDispatcher.fgLeaseKey("bgOnly"));

        for (int i = 0; i < 5; i++) {
            assertEquals("bgOnly", polledJobId(), "FG가 없으면 매회 BG가 나와야 함 (work-conserving)");
        }
        assertNull(dispatcher.poll(), "큐 소진 후엔 null");
    }

    @Test
    void 큐_소진으로_정리된_작업도_requeue하면_재등록된다() {
        dispatcher.enqueueJob("userA", "retryJob", tasks("retryJob", 1));

        assertEquals("retryJob", polledJobId());
        assertNull(dispatcher.poll(), "큐가 비었으니 null (링에서 정리됨)");

        // 실패한 페이지 재등록 (PageWorker 재시도 경로)
        dispatcher.requeue("userA", "retryJob", task("retryJob", 1));
        assertEquals("retryJob", polledJobId(), "재등록된 태스크가 다시 나와야 함");
    }

    @Test
    void 재시도_태스크는_큐_머리로_들어가_뒤_페이지보다_먼저_나온다() {
        dispatcher.enqueueJob("userA", "orderJob", tasks("orderJob", 3));

        String p1 = dispatcher.poll();
        assertTrue(p1.contains("\"pageNo\":1"));

        // 1페이지 실패 → 재등록: 큐 머리로 가서 2·3페이지보다 먼저 다시 나와야 함 (페이지 순서 유지)
        dispatcher.requeue("userA", "orderJob", p1);
        assertTrue(dispatcher.poll().contains("\"pageNo\":1"), "재시도 페이지가 최우선");
        assertTrue(dispatcher.poll().contains("\"pageNo\":2"));
        assertTrue(dispatcher.poll().contains("\"pageNo\":3"));
    }

    @Test
    void touchForeground는_30초_리스를_건다() {
        dispatcher.enqueueJob("userA", "leaseJob", tasks("leaseJob", 1));

        String key = JobDispatcher.fgLeaseKey("leaseJob");
        assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(key)));
        Long ttl = redisTemplate.getExpire(key);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 30, "TTL은 0~30초 사이여야 함, 실제: " + ttl);
    }

    @Test
    void 레거시_task_queue는_기동_시_새_구조로_이관된다() {
        // 구 형식(userId 없음) 태스크가 남아 있는 상황
        String legacy = "{\"jobId\":\"oldJob\",\"pageNo\":3,\"gcsPath\":\"p\",\"mode\":\"a\",\"totalPages\":5}";
        redisTemplate.opsForList().leftPush(JobDispatcher.LEGACY_TASK_QUEUE, legacy);

        Job job = Mockito.mock(Job.class);
        User user = Mockito.mock(User.class);
        when(job.getUser()).thenReturn(user);
        when(user.getId()).thenReturn(UUID.randomUUID());
        when(jobRepository.findById("oldJob")).thenReturn(Optional.of(job));

        dispatcher.migrateLegacyQueue();

        assertEquals(0, redisTemplate.opsForList().size(JobDispatcher.LEGACY_TASK_QUEUE));
        assertEquals("oldJob", polledJobId(), "이관된 태스크가 새 구조에서 나와야 함");
    }
}

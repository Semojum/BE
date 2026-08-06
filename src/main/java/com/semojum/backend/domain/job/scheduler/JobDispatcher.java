package com.semojum.backend.domain.job.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semojum.backend.domain.job.repository.JobRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 공정 스케줄러 — 단일 task_queue를 작업별 큐 + 2계층 라운드로빈으로 대체한다.
 * (설계: 노션 "[V3] 작업 스케줄링")
 *
 * - 작업별 큐(queue:job:{jobId})에 페이지를 쌓고, 꺼낼 때는
 *   유저 링 회전 → 그 유저의 작업 링 회전 → 페이지 1장 pop 순서로 고른다.
 *   → 유저 간 공평(작업을 몇 개 돌리든 1명 몫), 유저 안에서도 작업 간 공평.
 * - 우선순위 2단계: FG(사용자가 보는 중) : BG(앱 종료) = 4:1 가중 배분.
 *   티어는 FG 리스 키(sched:job:{jobId}:fg, TTL 30s)의 존재로 판정 —
 *   SSE 루프·status 폴링이 갱신하고, 끊기면 TTL 만료로 자연 강등(별도 이벤트 불필요).
 * - 선택 연산은 synchronized poll() 하나로 직렬화(BE 단일 인스턴스 전제).
 *   링·큐 상태는 전부 Redis라 BE 재시작에도 복구된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobDispatcher {

    private final RedisTemplate<String, String> redisTemplate;
    private final JobRepository jobRepository;
    private final ObjectMapper objectMapper;

    static final String USER_RING = "sched:ring:users";
    static final String LEGACY_TASK_QUEUE = "task_queue";
    static final Duration FG_LEASE = Duration.ofSeconds(30);
    private static final int BG_SHARE_PERIOD = 5; // 5회 중 1회는 BG 우선

    private final AtomicLong dispatchCounter = new AtomicLong();

    public static String jobQueueKey(String jobId) { return "queue:job:" + jobId; }
    static String userJobsKey(String userId) { return "sched:user:" + userId + ":jobs"; }
    static String fgLeaseKey(String jobId) { return "sched:job:" + jobId + ":fg"; }

    /**
     * Job 생성 시: 페이지 태스크 적재 + 링 등록. 방금 만든 사람은 보고 있으므로 FG로 시작.
     * 호출부(createJob)가 @Transactional이라 커밋 전에 적재하면 워커가 아직 커밋 안 된
     * Job/Page 행을 조회하다 "not found"로 재시도 1회를 태운다(실측) → 커밋 후로 미룬다.
     */
    public void enqueueJob(String userId, String jobId, List<String> tasks) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doEnqueueJob(userId, jobId, tasks);
                }
            });
        } else {
            doEnqueueJob(userId, jobId, tasks);
        }
    }

    private void doEnqueueJob(String userId, String jobId, List<String> tasks) {
        redisTemplate.opsForList().rightPushAll(jobQueueKey(jobId), tasks);
        register(userId, jobId);
        touchForeground(jobId);
    }

    /** 재시도·백프레셔: 자기 작업 큐 뒤에 재삽입. 큐가 비어 링에서 정리된 뒤일 수 있으니 재등록까지 */
    public void requeue(String userId, String jobId, String task) {
        if (userId == null) {
            // 레거시 태스크(userId 없음) 대비 — DB에서 소유자 조회
            userId = jobRepository.findById(jobId)
                    .map(job -> job.getUser().getId().toString())
                    .orElse(null);
            if (userId == null) {
                log.error("requeue 실패 — Job 소유자를 찾을 수 없음: jobId={}", jobId);
                return;
            }
        }
        redisTemplate.opsForList().rightPush(jobQueueKey(jobId), task);
        register(userId, jobId);
    }

    /**
     * FG 리스 갱신 — 이 작업의 사용자가 "보고 있다"는 신호.
     * SSE 폴링 루프(1초 주기)와 status 조회가 호출한다. 30초 안 갱신되면 BG로 자연 강등.
     */
    public void touchForeground(String jobId) {
        redisTemplate.opsForValue().set(fgLeaseKey(jobId), "1", FG_LEASE);
    }

    /**
     * 다음에 처리할 태스크 1건 선택. 없으면 null.
     * 선택 순서: 티어 결정(5회 중 4회 FG 우선) → 유저 링 회전 → 작업 링 회전 → 페이지 pop.
     * 우선 티어에 일감이 없으면 반대 티어를 쓴다(슬롯을 놀리지 않음 — work-conserving).
     */
    public synchronized String poll() {
        boolean bgFirst = dispatchCounter.getAndIncrement() % BG_SHARE_PERIOD == BG_SHARE_PERIOD - 1;
        String task = pollTier(!bgFirst);
        if (task == null) {
            task = pollTier(bgFirst);
        }
        return task;
    }

    private String pollTier(boolean wantForeground) {
        Long ringSize = redisTemplate.opsForList().size(USER_RING);
        if (ringSize == null || ringSize == 0) return null;

        for (int i = 0; i < ringSize; i++) {
            // 링 회전: 꼬리에서 하나 꺼내 머리로 — 방금 옮겨진 유저가 이번 차례
            String userId = redisTemplate.opsForList().rightPopAndLeftPush(USER_RING, USER_RING);
            if (userId == null) return null;

            String task = pollUser(userId, wantForeground);
            if (task != null) return task;

            // 작업 링이 빈 유저는 링에서 정리 (lazy cleanup)
            Long jobCount = redisTemplate.opsForList().size(userJobsKey(userId));
            if (jobCount == null || jobCount == 0) {
                redisTemplate.opsForList().remove(USER_RING, 0, userId);
            }
        }
        return null;
    }

    private String pollUser(String userId, boolean wantForeground) {
        String jobsKey = userJobsKey(userId);
        Long jobCount = redisTemplate.opsForList().size(jobsKey);
        if (jobCount == null || jobCount == 0) return null;

        for (int i = 0; i < jobCount; i++) {
            String jobId = redisTemplate.opsForList().rightPopAndLeftPush(jobsKey, jobsKey);
            if (jobId == null) return null;

            Long queueLen = redisTemplate.opsForList().size(jobQueueKey(jobId));
            if (queueLen == null || queueLen == 0) {
                // 페이지를 전부 꺼낸 작업은 링에서 정리 (재시도 시 requeue가 재등록)
                redisTemplate.opsForList().remove(jobsKey, 0, jobId);
                continue;
            }

            boolean isForeground = Boolean.TRUE.equals(redisTemplate.hasKey(fgLeaseKey(jobId)));
            if (isForeground != wantForeground) continue;

            return redisTemplate.opsForList().leftPop(jobQueueKey(jobId));
        }
        return null;
    }

    /** 링 등록 — 이미 있으면 위치를 건드리지 않는다(공정성 유지). 신규는 머리 쪽 = 기존 유저들 차례 이후 */
    private void register(String userId, String jobId) {
        if (redisTemplate.opsForList().indexOf(userJobsKey(userId), jobId) == null) {
            redisTemplate.opsForList().leftPush(userJobsKey(userId), jobId);
        }
        if (redisTemplate.opsForList().indexOf(USER_RING, userId) == null) {
            redisTemplate.opsForList().leftPush(USER_RING, userId);
        }
    }

    /**
     * 배포 전 단일 task_queue에 남아 있던 태스크를 새 구조로 이관한다(1회성).
     * 구 태스크에는 userId가 없어 DB에서 소유자를 조회해 채운다.
     */
    @PostConstruct
    public void migrateLegacyQueue() {
        int migrated = 0;
        while (true) {
            String task = redisTemplate.opsForList().rightPop(LEGACY_TASK_QUEUE);
            if (task == null) break;
            try {
                Map<String, Object> taskMap = new HashMap<>(objectMapper.readValue(task, Map.class));
                String jobId = (String) taskMap.get("jobId");
                requeue(null, jobId, objectMapper.writeValueAsString(taskMap));
                touchForeground(jobId);
                migrated++;
            } catch (Exception e) {
                log.error("레거시 task_queue 이관 실패, task 폐기(스테일 스케줄러가 정리): task={}", task, e);
            }
        }
        if (migrated > 0) {
            log.info("레거시 task_queue 이관 완료: {}건", migrated);
        }
    }
}

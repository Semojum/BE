package com.semojum.backend.domain.job.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.job.scheduler.JobDispatcher;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 변환 취소 — "변환 완료한 부분까지만 남기고 중단".
 *
 * <p>취소는 즉시가 아니라 수렴이다: 이미 AI 서버에 들어간 페이지(RUNNING)는 강제로 끊지 않고
 * 마무리까지 기다렸다가 저장한다(중간에 끊으면 슬롯·결과 정합이 깨진다). 흐름:
 * <ol>
 *   <li>취소 플래그를 먼저 세운다 — 이후 워커가 이 작업의 태스크를 집으면 처리하지 않고 버린다</li>
 *   <li>작업 큐를 배수한다 — 아직 워커에 안 잡힌 페이지들. 큐가 비면 스케줄러 링에서도
 *       자연히 정리된다(lazy cleanup) → 다른 작업이 슬롯을 이어받는다</li>
 *   <li>배수된 페이지는 CANCELED(과도 상태)로 표기</li>
 *   <li>인플라이트가 전부 끝나면 확정(finalize): 완료된 마지막 페이지(K)까지만 남기고
 *       그 뒤 페이지는 삭제 + total_pages=K로 축소. K보다 앞에 낀 미변환 페이지(재시도 대기 중이던 것)는
 *       페이지 번호 구멍을 만들지 않도록 BLOCKED로 남긴다. 완료가 하나도 없으면 전부 BLOCKED + FAILED</li>
 * </ol>
 *
 * <p>플래그 세팅과 큐 배수 사이에 워커가 태스크를 집는 경합은 워커 쪽 플래그 검사로 수렴한다 —
 * 플래그 이전에 검사를 통과한 태스크는 인플라이트로 취급(마무리), 이후는 폐기 후 {@link #cancelPage}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobCancelService {

    private final JobRepository jobRepository;
    private final PageRepository pageRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final Duration FLAG_TTL = Duration.ofHours(1);
    private static final List<String> TERMINAL = List.of("COMPLETED", "NEEDS_REVIEW", "BLOCKED");

    static String cancelFlagKey(String jobId) { return "job:" + jobId + ":canceled"; }
    static String pagesHashKey(String jobId) { return "job:" + jobId + ":pages"; }

    /** 워커가 태스크를 집은 직후·재시도 직전에 확인한다 */
    public boolean isCanceled(String jobId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(cancelFlagKey(jobId)));
    }

    /** 확정 결과 — status는 COMPLETED(부분 완료) 또는 FAILED */
    public record FinalizeResult(String status, int totalPages) {}

    @Transactional
    public Map<String, Object> cancel(String userId, String jobId) {
        // 본인 Job 검증 — 타인 소유는 404로 통일 (V3 관리 API 관례)
        Job job = jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));

        // 이미 끝난 작업이면 취소할 게 없다 — 멱등(모달을 띄운 사이 변환이 끝난 경우)
        if (!job.isInProgress()) {
            return buildResponse(jobId, job.getStatus(), job.getTotalPages(), 0, false);
        }

        // 1. 플래그 먼저 — 이 시점 이후 워커가 집는 이 작업의 태스크는 전부 폐기된다
        redisTemplate.opsForValue().set(cancelFlagKey(jobId), "1", FLAG_TTL);

        // 2. 큐 배수 — 아직 디스패치되지 않은 페이지. 빈 큐는 스케줄러가 lazy cleanup으로 링에서 제거
        List<Integer> drained = new ArrayList<>();
        while (true) {
            String task = redisTemplate.opsForList().leftPop(JobDispatcher.jobQueueKey(jobId));
            if (task == null) break;
            Integer pageNo = parsePageNo(task);
            if (pageNo != null) drained.add(pageNo);
        }

        // 3. 배수된 페이지 CANCELED 표기 (DB 과도 상태 + Redis)
        for (int pageNo : drained) {
            markPageCanceled(job, jobId, pageNo);
        }
        log.info("변환 취소 접수: jobId={}, 큐에서 회수 {}페이지", jobId, drained.size());

        // 4. 인플라이트가 없으면 여기서 확정, 있으면 마지막 인플라이트를 처리한 워커가 확정
        FinalizeResult result = tryFinalize(jobId);

        long inFlight = countRunning(jobId);
        if (result != null) {
            return buildResponse(jobId, result.status(), result.totalPages(), inFlight, true);
        }
        return buildResponse(jobId, "IN_PROGRESS", job.getTotalPages(), inFlight, true);
    }

    /**
     * 워커 경로 — 취소 플래그가 선 뒤에 집힌(또는 재시도하려던) 태스크를 폐기 처리.
     * gRPC를 보내지 않았으므로 "AI에 들어간 페이지"가 아니다 → CANCELED.
     */
    @Transactional
    public void cancelPage(String jobId, int pageNo) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null || !job.isInProgress()) {
            return; // 이미 확정된 뒤 도착한 낙오 태스크 — 해시를 되살리지 않는다
        }
        markPageCanceled(job, jobId, pageNo);
        tryFinalize(jobId);
    }

    private void markPageCanceled(Job job, String jobId, int pageNo) {
        pageRepository.findByJobAndPageNo(job, pageNo).ifPresent(page -> {
            if ("PENDING".equals(page.getStatus())) {
                page.updateStatus("CANCELED");
            }
        });
        redisTemplate.opsForHash().put(pagesHashKey(jobId), "page:" + pageNo, "CANCELED");
    }

    /**
     * 취소 확정 — 인플라이트(RUNNING)나 아직 상태가 안 잡힌(PENDING) 페이지가 남아 있으면 보류(null).
     * 그 페이지를 처리한 워커가 다시 호출해 수렴한다. synchronized: 단일 인스턴스 전제(스케줄러와 동일),
     * 내부 변이는 전부 멱등이라 커밋 경합이 겹쳐도 결과가 같다(finishJob은 상태 가드로 1회만 전이).
     */
    @Transactional
    public synchronized FinalizeResult tryFinalize(String jobId) {
        Map<Object, Object> hash = redisTemplate.opsForHash().entries(pagesHashKey(jobId));
        for (Map.Entry<Object, Object> e : hash.entrySet()) {
            if ("total_pages".equals(e.getKey())) continue;
            String v = (String) e.getValue();
            // RUNNING = AI 처리 중(마무리 대기). PENDING = 워커가 집었지만 아직 RUNNING 표기 전인 찰나 — 곧 결정된다
            if ("RUNNING".equals(v) || "PENDING".equals(v)) {
                return null;
            }
        }

        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null || !job.isInProgress()) {
            return null; // 이미 확정됨(중복 호출)
        }

        // 취소 기록 — 잘리기 전 원래 규모와 취소 시각 (native update의 flushAutomatically로 함께 반영됨)
        job.markCanceled(job.getTotalPages());

        List<Page> pages = pageRepository.findByJob(job);
        int lastKept = pages.stream()
                .filter(p -> TERMINAL.contains(p.getStatus()))
                .mapToInt(Page::getPageNo)
                .max().orElse(0);
        List<Page> canceled = pages.stream()
                .filter(p -> "CANCELED".equals(p.getStatus()))
                .toList();

        int finalTotal;
        if (lastKept == 0) {
            // 한 페이지도 변환 못 하고 취소 — 전부 BLOCKED로 남기고 FAILED (전면 실패와 동일한 모양)
            for (Page p : canceled) {
                p.updateStatus("BLOCKED");
                redisTemplate.opsForHash().put(pagesHashKey(jobId), "page:" + p.getPageNo(), "BLOCKED");
            }
            finalTotal = job.getTotalPages();
        } else {
            for (Page p : canceled) {
                if (p.getPageNo() < lastKept) {
                    // 완료 범위 중간에 낀 미변환 페이지(재시도 대기 중이던 것) — 번호 구멍을 만들지 않게 BLOCKED
                    p.updateStatus("BLOCKED");
                    redisTemplate.opsForHash().put(pagesHashKey(jobId), "page:" + p.getPageNo(), "BLOCKED");
                } else {
                    // 완료 범위 뒤쪽 — 잘라낸다 (변환 데이터가 없으므로 page_results FK 없음, S3 파일은 잡 삭제 시 프리픽스로 정리)
                    pageRepository.delete(p);
                    redisTemplate.opsForHash().delete(pagesHashKey(jobId), "page:" + p.getPageNo());
                }
            }
            finalTotal = lastKept;
            if (finalTotal != job.getTotalPages()) {
                jobRepository.updateTotalPages(jobId, finalTotal);
                redisTemplate.opsForHash().put(pagesHashKey(jobId), "total_pages", String.valueOf(finalTotal));
            }
        }

        // 종료 확정 — 성공이 하나라도 있으면 COMPLETED(부분 완료), 없으면 FAILED (기존 종료 판정과 동일 규칙)
        long successCount = pages.stream()
                .filter(p -> "COMPLETED".equals(p.getStatus()) || "NEEDS_REVIEW".equals(p.getStatus()))
                .count();
        int[] blockedPageNos = pages.stream()
                .filter(p -> "BLOCKED".equals(p.getStatus()))
                .mapToInt(Page::getPageNo)
                .sorted()
                .toArray();
        String finalStatus = successCount == 0 ? "FAILED" : "COMPLETED";
        jobRepository.finishJob(jobId, finalStatus, toPgIntArray(blockedPageNos));
        redisTemplate.expire(pagesHashKey(jobId), Duration.ofHours(1));

        log.info("변환 취소 확정: jobId={}, status={}, totalPages {}→{}, blocked={}",
                jobId, finalStatus, job.getTotalPages(), finalTotal, blockedPageNos.length);
        return new FinalizeResult(finalStatus, finalTotal);
    }

    private long countRunning(String jobId) {
        return redisTemplate.opsForHash().entries(pagesHashKey(jobId)).values().stream()
                .filter("RUNNING"::equals)
                .count();
    }

    private Integer parsePageNo(String task) {
        try {
            Map<?, ?> map = objectMapper.readValue(task, Map.class);
            return (Integer) map.get("pageNo");
        } catch (Exception e) {
            log.error("취소 중 태스크 파싱 실패, 무시: task={}", task, e);
            return null;
        }
    }

    private Map<String, Object> buildResponse(String jobId, String status, int totalPages,
                                              long inFlightPages, boolean canceled) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", jobId);
        result.put("canceled", canceled);
        result.put("status", status);
        result.put("totalPages", totalPages);
        result.put("inFlightPages", inFlightPages);
        return result;
    }

    // int[] → PostgreSQL integer[] 텍스트 리터럴 (ResultService와 동일 형식)
    private String toPgIntArray(int[] nums) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < nums.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(nums[i]);
        }
        return sb.append("}").toString();
    }
}

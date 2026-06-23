package com.semojum.backend.domain.job.scheduler;

import com.semojum.backend.domain.job.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

// 멈춘 Job 정리 안전망: 무진행 IN_PROGRESS / 고아 PENDING을 FAILED로 전이.
// WORKER_COUNT=1 환경이므로 큐에서 순서를 기다리는 정상 PENDING은
// 짧은 IN_PROGRESS 타임아웃이 아니라 긴 PENDING 타임아웃(12h)으로만 정리된다.
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleJobScheduler {

    private final JobRepository jobRepository;
    private final JobStaleProperties properties;

    @Scheduled(fixedRate = 5 * 60 * 1000L) // 5분 주기
    @Transactional
    public void failStaleJobs() {
        Instant now = Instant.now();

        int inProgress = jobRepository.failStaleInProgress(now.minus(properties.getInProgressTimeout()));
        int pending = jobRepository.failStalePending(now.minus(properties.getPendingTimeout()));

        if (inProgress > 0 || pending > 0) {
            log.info("stale-job 정리: IN_PROGRESS→FAILED {}건, PENDING→FAILED {}건", inProgress, pending);
        }
    }
}

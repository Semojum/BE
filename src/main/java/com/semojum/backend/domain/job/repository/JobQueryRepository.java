package com.semojum.backend.domain.job.repository;

import com.semojum.backend.domain.job.dto.JobSearchCondition;
import com.semojum.backend.domain.job.entity.Job;

import java.util.List;
import java.util.UUID;

/** 마이페이지 목록 조회 — 동적 필터 + 커서 페이지네이션 (JobRepository가 상속). */
public interface JobQueryRepository {

    /** 조건에 맞는 활성 작업을 정렬해 size+1개까지 조회한다(다음 페이지 존재 판단용). */
    List<Job> search(UUID userId, JobSearchCondition condition);
}

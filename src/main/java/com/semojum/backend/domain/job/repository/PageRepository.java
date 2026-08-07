package com.semojum.backend.domain.job.repository;

import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PageRepository extends JpaRepository<Page, UUID> {
    List<Page> findByJobId(String jobId);
    Optional<Page> findByJobAndPageNo(Job job, int pageNo);
    long countByJobAndStatusIn(Job job, List<String> statuses);
    List<Page> findByJobAndStatus(Job job, String status);

    List<Page> findByJob(Job job);
}
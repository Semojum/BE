package com.semojum.backend.domain.job.repository;

import com.semojum.backend.domain.job.entity.Page;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PageRepository extends JpaRepository<Page, UUID> {
    List<Page> findByJobId(String jobId);
}
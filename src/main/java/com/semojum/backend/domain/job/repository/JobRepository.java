package com.semojum.backend.domain.job.repository;

import com.semojum.backend.domain.job.entity.Job;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, String> {
    Optional<Job> findByIdAndUserId(String id, UUID userId);
}

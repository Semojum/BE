package com.semojum.backend.domain.job.repository;

import com.semojum.backend.domain.job.entity.Job;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRepository extends JpaRepository<Job, String> {
}
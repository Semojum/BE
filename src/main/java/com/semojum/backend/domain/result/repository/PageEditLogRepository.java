package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.PageEditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PageEditLogRepository extends JpaRepository<PageEditLog, UUID> {
}

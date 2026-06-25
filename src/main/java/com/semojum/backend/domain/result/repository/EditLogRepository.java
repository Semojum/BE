package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.EditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EditLogRepository extends JpaRepository<EditLog, UUID> {
}

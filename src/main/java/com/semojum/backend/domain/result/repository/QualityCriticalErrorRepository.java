package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.QualityCriticalError;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface QualityCriticalErrorRepository extends JpaRepository<QualityCriticalError, UUID> {}

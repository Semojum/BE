package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.BrailleElement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BrailleElementRepository extends JpaRepository<BrailleElement, UUID> {}

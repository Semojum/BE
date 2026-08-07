package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.RuleTrail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RuleTrailRepository extends JpaRepository<RuleTrail, UUID> {
    List<RuleTrail> findByElementId(UUID elementId);
}

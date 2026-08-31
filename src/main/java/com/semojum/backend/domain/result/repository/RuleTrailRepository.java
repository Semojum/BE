package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.RuleTrail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface RuleTrailRepository extends JpaRepository<RuleTrail, UUID> {
    List<RuleTrail> findByElementId(UUID elementId);

    // 페이지의 모든 요소를 한 번에 — 요소마다 findByElementId를 부르면 요소 수만큼 왕복이 생긴다(N+1).
    // 실측(2026-08-31): 요소 95개 페이지의 응답이 266ms(콜드 1.4s) → 배치 조회로 쿼리 96개가 6개로 줄어든다
    List<RuleTrail> findByElementIdIn(Collection<UUID> elementIds);
}

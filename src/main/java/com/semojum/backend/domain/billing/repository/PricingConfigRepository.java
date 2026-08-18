package com.semojum.backend.domain.billing.repository;

import com.semojum.backend.domain.billing.entity.PricingConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PricingConfigRepository extends JpaRepository<PricingConfig, Long> {

    // 최신 행 = 현재 적용 중인 단가표
    Optional<PricingConfig> findTopByOrderByIdDesc();
}

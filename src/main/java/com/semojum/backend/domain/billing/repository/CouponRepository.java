package com.semojum.backend.domain.billing.repository;

import com.semojum.backend.domain.billing.entity.Coupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface CouponRepository extends JpaRepository<Coupon, UUID> {

    // 차감 시 유효 쿠폰 조회 — 워커 4개가 병렬 차감하므로 행 잠금으로 잔량 초과 소진 방지
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Coupon c WHERE c.organizationId = :orgId " +
            "AND c.startsOn <= :today AND c.endsOn >= :today ORDER BY c.createdAt")
    List<Coupon> findActiveForUpdate(@Param("orgId") UUID orgId, @Param("today") LocalDate today);

    List<Coupon> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);
}

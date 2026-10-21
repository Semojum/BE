package com.semojum.backend.domain.org.repository;

import com.semojum.backend.domain.org.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    boolean existsByCode(String code);

    /** 배정 크레딧만 — 잔여 크레딧 계산용(엔티티를 올리지 않는다). 없는 기관이면 null */
    @org.springframework.data.jpa.repository.Query(
            "SELECT o.creditAllocated FROM Organization o WHERE o.id = :orgId")
    Long findCreditAllocated(@org.springframework.data.repository.query.Param("orgId") UUID orgId);

    // 자동 코드(orgNN) 발급용 — 기존 코드 전체 (기관 수는 수십 개 수준)
    @Query("select o.code from Organization o")
    List<String> findAllCodes();
}

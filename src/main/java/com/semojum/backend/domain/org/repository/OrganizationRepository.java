package com.semojum.backend.domain.org.repository;

import com.semojum.backend.domain.org.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    boolean existsByCode(String code);

    // 자동 코드(orgNN) 발급용 — 기존 코드 전체 (기관 수는 수십 개 수준)
    @Query("select o.code from Organization o")
    List<String> findAllCodes();
}

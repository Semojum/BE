package com.semojum.backend.domain.support.repository;

import com.semojum.backend.domain.support.entity.Inquiry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InquiryRepository extends JpaRepository<Inquiry, UUID> {

    List<Inquiry> findAllByOrderByCreatedAtDesc();

    List<Inquiry> findByStatusOrderByCreatedAtDesc(String status);

    List<Inquiry> findByTypeOrderByCreatedAtDesc(String type);

    List<Inquiry> findByStatusAndTypeOrderByCreatedAtDesc(String status, String type);

    // T2 기관 화면 — 자기 기관의 요청·문의와 처리 상태
    List<Inquiry> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    // 메일 폴링 중복 방지 (DB 유니크가 최종 방어선)
    boolean existsByMailUid(String mailUid);
}

package com.semojum.backend.domain.support.repository;

import com.semojum.backend.domain.support.entity.Notice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface NoticeRepository extends JpaRepository<Notice, UUID> {

    // 운영자 보낸 공지 목록 (최신 등록순)
    List<Notice> findAllByOrderByCreatedAtDesc();

    // 기관 관리자에게 노출 중인 공지 — 전체(null) + 자기 기관, 노출 기간 내 (기간 지나면 자동 종료)
    @Query("SELECT n FROM Notice n WHERE (n.targetOrganizationId IS NULL OR n.targetOrganizationId = :orgId) " +
            "AND n.startsOn <= :today AND n.endsOn >= :today ORDER BY n.startsOn DESC, n.createdAt DESC")
    List<Notice> findVisibleForOrganization(@Param("orgId") UUID orgId, @Param("today") LocalDate today);

    // 로그인 전 공개 공지 — 전체 대상(기관 지정 없음)만, 노출 기간 내
    @Query("SELECT n FROM Notice n WHERE n.targetOrganizationId IS NULL " +
            "AND n.startsOn <= :today AND n.endsOn >= :today ORDER BY n.startsOn DESC, n.createdAt DESC")
    List<Notice> findVisibleForAll(@Param("today") LocalDate today);
}

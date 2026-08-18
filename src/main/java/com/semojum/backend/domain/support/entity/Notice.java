package com.semojum.backend.domain.support.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 공지 — 운영자(T1-10)가 작성, 기관 관리자 화면(T2)에 노출.
 * targetOrganizationId null = 전체 공지. 노출 기간이 지나면 자동 종료(조회 시 기간 필터).
 */
@Entity
@Table(name = "notices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @Column(name = "target_organization_id", columnDefinition = "uuid")
    private UUID targetOrganizationId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Builder
    public Notice(UUID targetOrganizationId, String title, String body,
                  LocalDate startsOn, LocalDate endsOn) {
        this.targetOrganizationId = targetOrganizationId;
        this.title = title;
        this.body = body;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
    }
}

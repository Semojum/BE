package com.semojum.backend.domain.org.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

// V3: 계정은 기관 소속으로 발급되며, 보관 정책이 기관의 계약 만료일을 참조한다.
@Entity
@Table(name = "organizations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @Column(nullable = false)
    private String name;

    // 계정 loginId 프리픽스 (예: kblib → kblib01, kblib02…). 소문자 영숫자, 전체 유일
    @Column(nullable = false, unique = true, length = 12)
    private String code;

    // 계약 만료일 (마이페이지 보관 기간 기준)
    private LocalDate contractExpiresAt;

    @Column(nullable = false)
    private String status; // ACTIVE | EXPIRED

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public Organization(String name, String code, LocalDate contractExpiresAt) {
        this.name = name;
        this.code = code;
        this.contractExpiresAt = contractExpiresAt;
        this.status = "ACTIVE";
        this.createdAt = LocalDateTime.now();
    }
}

package com.semojum.backend.domain.job.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "pages", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"job_id", "page_no"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Page {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @Column(nullable = false)
    private int pageNo;

    @Column(nullable = false)
    private String pdfPath;

    // 원본 미리보기용 렌더 이미지(JPEG) 경로 — FE 좌측 패널이 pdf.js 대신 <img>로 바로 그린다.
    // null = 아직 생성 전이거나 렌더 실패 → 조회 API가 원본 PDF로 폴백한다 (V29)
    @Column
    private String imagePath;

    @Column(nullable = false)
    private String status;

    // 차단(BLOCKED) 사유 (V43) — null = 사유 없음(AI 오류 재시도 초과 · 취소). 지금은 크레딧 부족 하나
    @Column(name = "blocked_reason", length = 40)
    private String blockedReason;

    /** 기관 잔여 크레딧이 0 이하라 AI에 보내지 않고 막은 쪽 */
    public static final String BLOCKED_CREDIT_EXHAUSTED = "CREDIT_EXHAUSTED";

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Builder
    public Page(Job job, int pageNo, String pdfPath) {
        this.job = job;
        this.pageNo = pageNo;
        this.pdfPath = pdfPath;
        this.status = "PENDING";
        this.createdAt = LocalDateTime.now();
    }

    public void updateStatus(String status) {
        this.status = status;
    }

    public void block(String reason) {
        this.status = "BLOCKED";
        this.blockedReason = reason;
    }

    public void updateImagePath(String imagePath) {
        this.imagePath = imagePath;
    }
}
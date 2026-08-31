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

    public void updateImagePath(String imagePath) {
        this.imagePath = imagePath;
    }
}
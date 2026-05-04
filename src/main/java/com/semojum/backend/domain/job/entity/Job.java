package com.semojum.backend.domain.job.entity;

import com.semojum.backend.domain.auth.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Job {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String mode;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int totalPages;

    @Column(columnDefinition = "integer[]")
    private int[] failedPages;

    private String originalFilePath;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @Builder
    public Job(String id, User user, String mode, int totalPages) {
        this.id = id;
        this.user = user;
        this.mode = mode;
        this.totalPages = totalPages;
        this.status = "PENDING";
        this.failedPages = new int[]{};
        this.startedAt = LocalDateTime.now();
    }
}
package com.semojum.backend.domain.board.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * QnA 게시판 글 (V39, 기획 6-2 · 피그마 D8).
 *
 * <p>{@code id}가 곧 화면의 글 번호다 — 지워도 뒤 번호를 당기지 않는다. 삭제는 {@code deletedAt}
 * 표시만 한다(답변·알림이 이 글을 가리킨다). 운영자가 숨긴 글({@code hidden})은 운영자에게만 보인다.
 */
@Entity
@Getter
@Table(name = "board_posts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BoardPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    // 비밀글 — 목록엔 제목이 보이고 열람은 작성자·운영자만
    @Column(nullable = false)
    private boolean secret;

    @Column(name = "view_count", nullable = false)
    private int viewCount;

    @Column(nullable = false)
    private boolean hidden;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public BoardPost(UUID userId, String title, String content, boolean secret) {
        this.userId = userId;
        this.title = title;
        this.content = content;
        this.secret = secret;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public void update(String title, String content, boolean secret) {
        this.title = title;
        this.content = content;
        this.secret = secret;
        this.updatedAt = LocalDateTime.now();
    }

    /** 조회수 — 상세를 열 때마다 +1(작성자 본인·반복 조회도 센다, 2026-10-14 결정) */
    public void increaseViewCount() {
        this.viewCount++;
    }

    public void updateHidden(boolean hidden) {
        this.hidden = hidden;
    }

    public void delete() {
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}

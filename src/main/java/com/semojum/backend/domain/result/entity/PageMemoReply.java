package com.semojum.backend.domain.result.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 줄 메모의 답글 (V42). .smj 파일을 받은 사람이 메모에 이어 단다 — 작성자는 앱이 보낸 이름 그대로다.
 * 메모와 함께 쪽 단위로 통째 교체된다.
 */
@Entity
@Getter
@Table(name = "page_memo_replies")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PageMemoReply {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "memo_id", nullable = false)
    private Long memoId;

    @Column(length = PageMemo.AUTHOR_MAX)
    private String author;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder
    public PageMemoReply(Long memoId, String author, String body, LocalDateTime createdAt) {
        this.memoId = memoId;
        this.author = author;
        this.body = body;
        // 파일에 든 작성 시각을 그대로 보내면 보존한다 — 없으면 지금
        this.createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }
}

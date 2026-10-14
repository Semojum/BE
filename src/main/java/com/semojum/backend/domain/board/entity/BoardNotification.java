package com.semojum.backend.domain.board.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 답변 알림 (V39, 피그마 D8-2 「알림」) — 글 하나에 하나.
 *
 * <p>「글쓴이에게 알림 보내기」를 체크한 답변만 알림을 만든다. 이미 있으면 새로 만들지 않고 시각을
 * 고쳐 다시 안 읽음으로 돌린다. 답변 수는 저장하지 않고 조회 때 센다(지운 답변이 빠지도록).
 */
@Entity
@Getter
@Table(name = "board_notifications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BoardNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "post_id", nullable = false, unique = true)
    private Long postId;

    @Column(name = "last_answered_at", nullable = false)
    private LocalDateTime lastAnsweredAt;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Builder
    public BoardNotification(UUID userId, Long postId, LocalDateTime lastAnsweredAt) {
        this.userId = userId;
        this.postId = postId;
        this.lastAnsweredAt = lastAnsweredAt;
        this.read = false;
    }

    public void renew(LocalDateTime answeredAt) {
        this.lastAnsweredAt = answeredAt;
        this.read = false;
    }

    public void markRead() {
        this.read = true;
    }
}

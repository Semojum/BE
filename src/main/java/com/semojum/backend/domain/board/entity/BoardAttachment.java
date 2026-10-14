package com.semojum.backend.domain.board.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 게시글 첨부 (V39) — S3 board/{postId}/, presigned로만 내려준다. 「현재 작업 정보」도 앱이 만든 일반 첨부다 */
@Entity
@Getter
@Table(name = "board_attachments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BoardAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "file_name", nullable = false, length = 300)
    private String fileName;

    @Column(name = "content_type", length = 150)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_path", nullable = false, length = 400)
    private String storagePath;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public BoardAttachment(Long postId, String fileName, String contentType, long sizeBytes, String storagePath) {
        this.postId = postId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.storagePath = storagePath;
        this.createdAt = LocalDateTime.now();
    }
}

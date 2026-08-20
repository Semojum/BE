package com.semojum.backend.domain.support.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** 문의 첨부 (V27) — 메일 문의의 첨부·인라인 이미지. S3 inquiries/{inquiryId}/, presigned로만 조회 */
@Entity
@Table(name = "inquiry_attachments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InquiryAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @Column(name = "inquiry_id", nullable = false, columnDefinition = "uuid")
    private UUID inquiryId;

    @Column(name = "file_name", nullable = false, length = 300)
    private String fileName;

    @Column(name = "content_type", length = 150)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_path", nullable = false, length = 400)
    private String storagePath;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Builder
    public InquiryAttachment(UUID inquiryId, String fileName, String contentType,
                             long sizeBytes, String storagePath) {
        this.inquiryId = inquiryId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.storagePath = storagePath;
    }
}

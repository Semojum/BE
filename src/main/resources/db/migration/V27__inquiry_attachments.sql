-- V27: 문의 첨부파일 — 메일 문의의 첨부·인라인 이미지를 S3에 보관하고 T1-9에서 확인 (2026-08-20)
-- 파일 실체는 S3 inquiries/{inquiryId}/ 아래, 공개 정책 밖(presigned GET 전용)
CREATE TABLE inquiry_attachments (
    id uuid PRIMARY KEY,
    inquiry_id uuid NOT NULL REFERENCES inquiries(id),
    file_name varchar(300) NOT NULL,
    content_type varchar(150),
    size_bytes bigint NOT NULL,
    storage_path varchar(400) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_inquiry_attachments_inquiry ON inquiry_attachments(inquiry_id);

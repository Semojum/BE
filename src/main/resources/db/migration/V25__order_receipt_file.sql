-- V25: 주문 증빙(계산서·전표) 파일 — 운영자가 업로드, 기관 관리자가 T2 주문 내역에서 내려받기
-- 파일 실체는 S3 receipts/{orderId}/ 아래, 공개 정책 밖(presigned GET 전용)
ALTER TABLE orders ADD COLUMN receipt_file_key varchar(300);
ALTER TABLE orders ADD COLUMN receipt_file_name varchar(200);
ALTER TABLE orders ADD COLUMN receipt_uploaded_at timestamptz;

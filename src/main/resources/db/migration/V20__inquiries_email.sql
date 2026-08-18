-- V20: 문의 메일 연동 (T1-9 — contact@semo-jum.com 받은편지함을 같은 문의 목록으로)
-- 메일 문의는 type='EMAIL', 보낸 사람 자리에 sender_email 표시. 나머지 양식·상태 관리는 동일
ALTER TABLE inquiries ADD COLUMN IF NOT EXISTS sender_email VARCHAR(100);   -- 메일 문의의 보낸 사람
ALTER TABLE inquiries ADD COLUMN IF NOT EXISTS subject      VARCHAR(300);   -- 메일 제목
ALTER TABLE inquiries ADD COLUMN IF NOT EXISTS mail_uid     VARCHAR(60);    -- "UIDVALIDITY:UID" — 재수집 중복 방지

CREATE UNIQUE INDEX IF NOT EXISTS uq_inquiries_mail_uid ON inquiries (mail_uid) WHERE mail_uid IS NOT NULL;

-- V21: 기관·계정 삭제 (T1-6) — 소프트 삭제
-- 실삭제(자료 정리)는 보관 기간 정책 확정 후 별도 — 지금은 삭제 표식 + 잠금(로그인 차단)까지
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
ALTER TABLE users         ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

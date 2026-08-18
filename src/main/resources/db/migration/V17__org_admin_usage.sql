-- V17: T2 기관 관리 · T3 사용량 (관리자 대시보드 V11 기획)
-- 기관: 할당 크레딧·계약 시작일·계약 구분 / 계정: 별칭·마지막 로그인
-- 역할 ROLE_ORG_ADMIN은 users.role(varchar)에 새 값만 추가 — DDL 불필요

ALTER TABLE organizations ADD COLUMN IF NOT EXISTS credit_allocated    BIGINT      NOT NULL DEFAULT 0;  -- 계약으로 받은 총량 (운영자 설정)
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS contract_started_at DATE;                            -- 계약 시작일 (만료일은 기존 contract_expires_at)
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS contract_type       VARCHAR(20) NOT NULL DEFAULT 'PAID';  -- PAID(유료) | TRIAL(체험) | INTERNAL(내부)

ALTER TABLE users ADD COLUMN IF NOT EXISTS alias         VARCHAR(50);   -- 별칭 — 기관 관리자가 역할명으로 지정 ("수학 담당")
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ;   -- 마지막 로그인 시각 (T1-6·T2 소속 계정 표)

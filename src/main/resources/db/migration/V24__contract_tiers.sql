-- V24: 계약 유형 개편 (기획 변경 2026-08-18) — PAID/TRIAL/INTERNAL → 5종
--   유료: BASIC(200원/크레딧) · STANDARD(150원) · PREMIUM(120원)
--   무료: FREE(무료 체험 — 몇 페이지 사용해보기) · COUPON(쿠폰 제공 방식)
-- 환산 매출 단가는 전역(creditPriceKrw) → 계약 유형별 맵(creditPricesByContract)으로

-- 기존 데이터 변환: PAID는 유료 최저 단계(BASIC), TRIAL·INTERNAL은 무료 체험(FREE)
UPDATE organizations SET contract_type = 'BASIC' WHERE contract_type = 'PAID';
UPDATE organizations SET contract_type = 'FREE'  WHERE contract_type IN ('TRIAL', 'INTERNAL');

-- 신규 기관 기본값: FREE — 유료 전환은 운영자가 명시적으로 설정(과금 실수 방지)
ALTER TABLE organizations ALTER COLUMN contract_type SET DEFAULT 'FREE';

-- 단가 관리 변수 교체 (biz 확정 단가: 2026-08-18)
UPDATE pricing_configs
SET config = (config - 'creditPriceKrw')
    || '{"creditPricesByContract": {"BASIC": 200, "STANDARD": 150, "PREMIUM": 120, "FREE": 0, "COUPON": 0}}'::jsonb;

-- V23: 쿠폰 (T1-7) — 체험·무료 제공은 쿠폰으로. 차감은 쿠폰부터, 소진되면 계약 크레딧에서(기획 확정)
CREATE TABLE IF NOT EXISTS coupons (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL,
    name            VARCHAR(100) NOT NULL,        -- 예: "PoC 체험"
    credit_amount   BIGINT       NOT NULL,        -- 쿠폰 크레딧 총량
    starts_on       DATE         NOT NULL,
    ends_on         DATE         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_coupons_org ON coupons (organization_id, starts_on, ends_on);

-- 차감의 출처 — 잔여·수익성 계산이 갈린다:
--   CONTRACT: 계약 크레딧에서 차감 (기관 잔여에서 빠지고, 수익성 환산 매출의 축)
--   COUPON  : 쿠폰에서 차감 (계약 잔여 불변·매출 0 — 수익성에서 원가만큼 마이너스)
ALTER TABLE credit_transactions ADD COLUMN IF NOT EXISTS source    VARCHAR(20) NOT NULL DEFAULT 'CONTRACT';
ALTER TABLE credit_transactions ADD COLUMN IF NOT EXISTS coupon_id UUID;
CREATE INDEX IF NOT EXISTS idx_credit_tx_coupon ON credit_transactions (coupon_id);

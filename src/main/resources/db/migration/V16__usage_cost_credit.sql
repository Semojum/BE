-- V16: 사용량·원가·크레딧 (proto 08.17 UsageReport 대응)
-- AI는 측정값(layout_type·모델별 토큰·gpu_time_ms)만 보내고, 금액·크레딧은 BE가 관리 변수로 계산한다.
-- 단가·환율은 수시로 바뀌므로 쪽 처리 시점에 계산한 금액·크레딧을 확정 저장하고(과거 기록 불변),
-- 원자료(토큰·gpu_time)도 함께 남겨 감사·재검산이 가능하게 한다.

-- ── 1. 단가·배율 관리 변수 (관리자 페이지에서 수시 수정 — 행 추가 = 이력) ──
CREATE TABLE IF NOT EXISTS pricing_configs (
    id         BIGSERIAL PRIMARY KEY,
    config     JSONB       NOT NULL,   -- modelPrices / gpuUsdPerHour / usdKrw / cardFeeRate / creditMultiplier
    note       TEXT,                   -- 변경 사유 (예: "환율 갱신")
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 초기값 (AI팀 노션 "BE 관리 변수" 2026-08-17 확정본)
INSERT INTO pricing_configs (config, note)
SELECT '{
  "modelPrices": {
    "claude-sonnet-5": {"input": 3.00,  "output": 15.00},
    "claude-opus-4-8": {"input": 15.00, "output": 75.00},
    "gpt-4o":          {"input": 2.50,  "output": 10.00}
  },
  "gpuUsdPerHour": 1.006,
  "usdKrw": 1390,
  "cardFeeRate": 0.010,
  "creditMultiplier": {
    "PAGE_LAYOUT_UNSPECIFIED": 0,
    "PAGE_LAYOUT_TEXT":        1,
    "PAGE_LAYOUT_FORMULA":     2,
    "PAGE_LAYOUT_TABLE":       3,
    "PAGE_LAYOUT_VISUAL":      5
  }
}'::jsonb, '초기 시드 (biz 확정 2026-08-17)'
WHERE NOT EXISTS (SELECT 1 FROM pricing_configs);

-- ── 2. page_results에 사용량·계산 결과 (BLOCKED 응답에도 usage_report가 실리므로 여기 저장) ──
ALTER TABLE page_results ADD COLUMN IF NOT EXISTS layout_type       VARCHAR(32);
ALTER TABLE page_results ADD COLUMN IF NOT EXISTS gpu_time_ms       BIGINT;
ALTER TABLE page_results ADD COLUMN IF NOT EXISTS model_usage       JSONB;           -- [{model, calls, inputTokens, outputTokens}]
ALTER TABLE page_results ADD COLUMN IF NOT EXISTS llm_cost_usd      NUMERIC(14,9);   -- 처리 시점 확정값
ALTER TABLE page_results ADD COLUMN IF NOT EXISTS gpu_cost_usd      NUMERIC(14,9);
ALTER TABLE page_results ADD COLUMN IF NOT EXISTS cost_krw          NUMERIC(16,3);   -- 참고값 (정본은 USD)
ALTER TABLE page_results ADD COLUMN IF NOT EXISTS cost_uncertain    BOOLEAN NOT NULL DEFAULT FALSE;  -- 단가표에 없는 모델 포함 = 미계상
ALTER TABLE page_results ADD COLUMN IF NOT EXISTS pricing_config_id BIGINT;          -- 계산에 쓴 단가표 판

-- ── 3. 크레딧 차감 로그 (성공한 쪽만, UNSPECIFIED의 0 차감도 기록 — 고객 검산용) ──
CREATE TABLE IF NOT EXISTS credit_transactions (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID        NOT NULL,
    organization_id UUID,
    job_id          VARCHAR(64) NOT NULL,
    page_no         INTEGER     NOT NULL,
    layout_type     VARCHAR(32),
    amount          INTEGER     NOT NULL,   -- 차감 크레딧 (0 포함)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 쪽당 차감은 1회뿐 — 워커 재시도로 save()가 재진입해도 이중 차감 불가(최종 방어선)
CREATE UNIQUE INDEX IF NOT EXISTS uq_credit_tx_job_page ON credit_transactions (job_id, page_no);
CREATE INDEX IF NOT EXISTS idx_credit_tx_job  ON credit_transactions (job_id);
CREATE INDEX IF NOT EXISTS idx_credit_tx_user ON credit_transactions (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_credit_tx_org  ON credit_transactions (organization_id, created_at);

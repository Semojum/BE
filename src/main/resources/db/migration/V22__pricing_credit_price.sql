-- V22: 크레딧 판매 단가(creditPriceKrw) — T1-2 기관별 수익성의 "환산 매출 = 차감 크레딧 × 단가" 축
-- 임시값 240원 = 기획서 수익성 표 역산(4,600크레딧 → ₩1,104,000). biz 확정 시 PUT /api/admin/pricing으로 교체
-- 기존 판들에 키를 주입해 과거 판 조회 시에도 값이 있게 한다 (수익성은 조회 시점 최신 판으로 환산)
UPDATE pricing_configs
SET config = config || '{"creditPriceKrw": 240}'::jsonb
WHERE NOT (config ? 'creditPriceKrw');

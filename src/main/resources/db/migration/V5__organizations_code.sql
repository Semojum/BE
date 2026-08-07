-- 기관 코드(code) 도입 — 계정 loginId 프리픽스 (예: kblib → kblib01, kblib02…)
-- 기존 기관은 생성순으로 orgNN 자동 부여

ALTER TABLE organizations ADD COLUMN IF NOT EXISTS code varchar(12);

WITH numbered AS (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM organizations
    WHERE code IS NULL
)
UPDATE organizations o
SET code = 'org' || lpad(numbered.rn::text, 2, '0')
FROM numbered
WHERE o.id = numbered.id;

ALTER TABLE organizations ALTER COLUMN code SET NOT NULL;
ALTER TABLE organizations ADD CONSTRAINT uq_organizations_code UNIQUE (code);

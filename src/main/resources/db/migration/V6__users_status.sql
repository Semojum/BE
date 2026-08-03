-- 계정 상태(status) 도입 — ACTIVE | INACTIVE. INACTIVE면 로그인·토큰 재발급 차단
-- DEFAULT를 두어 마이그레이션이 구버전 코드보다 먼저 적용돼도 INSERT가 깨지지 않게 한다

ALTER TABLE users ADD COLUMN IF NOT EXISTS status varchar(20) DEFAULT 'ACTIVE';
UPDATE users SET status = 'ACTIVE' WHERE status IS NULL;
ALTER TABLE users ALTER COLUMN status SET NOT NULL;

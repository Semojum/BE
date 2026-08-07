-- 계정 역할(role) 도입 — ROLE_ADMIN(운영·테스트용) | ROLE_USER(점역사)
-- DEFAULT를 두어 마이그레이션이 구버전 코드보다 먼저 적용돼도 INSERT가 깨지지 않게 한다

ALTER TABLE users ADD COLUMN IF NOT EXISTS role varchar(20) DEFAULT 'ROLE_USER';
UPDATE users SET role = 'ROLE_USER' WHERE role IS NULL;
ALTER TABLE users ALTER COLUMN role SET NOT NULL;

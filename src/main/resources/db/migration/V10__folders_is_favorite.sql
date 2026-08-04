-- 폴더 즐겨찾기 — 파일(jobs.is_favorite)과 동일하게 폴더도 즐겨찾기 대상
-- DEFAULT를 두어 마이그레이션이 구버전 코드보다 먼저 적용돼도 INSERT가 깨지지 않게 한다

ALTER TABLE folders ADD COLUMN IF NOT EXISTS is_favorite boolean DEFAULT false;
UPDATE folders SET is_favorite = false WHERE is_favorite IS NULL;
ALTER TABLE folders ALTER COLUMN is_favorite SET NOT NULL;

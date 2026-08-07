-- 마이페이지 즐겨찾기 — 목록 필터·정렬용
-- DEFAULT를 두어 마이그레이션이 구버전 코드보다 먼저 적용돼도 INSERT가 깨지지 않게 한다

ALTER TABLE jobs ADD COLUMN IF NOT EXISTS is_favorite boolean DEFAULT false;
UPDATE jobs SET is_favorite = false WHERE is_favorite IS NULL;
ALTER TABLE jobs ALTER COLUMN is_favorite SET NOT NULL;

-- 목록 조회(사용자+활성+정렬) 인덱스
CREATE INDEX IF NOT EXISTS idx_jobs_user_active_modified
    ON jobs (user_id, last_modified_at DESC, id DESC)
    WHERE deleted_at IS NULL;

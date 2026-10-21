-- 쪽이 차단(BLOCKED)된 사유 (2026-10-21) — 지금은 크레딧 부족(CREDIT_EXHAUSTED) 하나.
-- null = 사유 없음(AI 오류 재시도 초과 · 취소 등 종전 경로). 앱이 「크레딧 부족」을 따로 보여주려고 둔다.
-- 블루그린 공존 · 공유 RDS: 칼럼만 더하므로 구버전 코드는 이 칼럼을 모르고 그대로 돈다
ALTER TABLE pages ADD COLUMN IF NOT EXISTS blocked_reason varchar(40);

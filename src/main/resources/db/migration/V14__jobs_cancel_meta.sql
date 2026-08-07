-- 변환 취소 기록 — 결과물은 완료분까지로 잘리지만(total_pages 축소) 원래 규모와 취소 시각은 남긴다.
-- 운영 통계·CS("왜 내 문서가 358페이지죠?") 대응용 메타. 카드·편집·조판 로직에는 영향 없음.
ALTER TABLE jobs ADD COLUMN canceled_at timestamptz;
ALTER TABLE jobs ADD COLUMN original_total_pages int;

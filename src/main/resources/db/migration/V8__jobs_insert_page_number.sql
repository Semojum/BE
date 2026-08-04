-- 업로드 시 선택한 "페이지 번호 삽입" 여부 — 점자 판면 마지막 줄 쪽번호 표기 기준
-- DEFAULT를 두어 마이그레이션이 구버전 코드보다 먼저 적용돼도 INSERT가 깨지지 않게 한다

ALTER TABLE jobs ADD COLUMN IF NOT EXISTS insert_page_number boolean DEFAULT false;
UPDATE jobs SET insert_page_number = false WHERE insert_page_number IS NULL;
ALTER TABLE jobs ALTER COLUMN insert_page_number SET NOT NULL;

-- V30 (2026-09-01): 업로드 조판 옵션 + 규정 출처 상세
-- 공유 DB 안전성: 전부 nullable 컬럼 추가라 구버전 코드가 몰라도 무해

-- 1) 업로드 시 고른 조판 옵션 (한 줄 칸 수·한 면 줄 수·페이지행·꼬리말 정렬·고급 점역 등).
--    기획이 확정 전이라 컬럼을 쪼개지 않고 jsonb 한 칸에 담는다 — 항목이 바뀌어도 마이그레이션이 필요 없다.
--    null = 옵션 없이 만든 기존 작업 → 코드가 기본값(32칸×26줄·홀수 면)으로 처리한다.
--    구 jobs.insert_page_number는 그대로 둔다(기존 작업 호환) — 옵션이 있으면 옵션이 이긴다.
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS layout_options jsonb;

-- 2) 규정 출처 상세 (proto 0901) — 어느 기관의 몇 년 판 어느 조문인지까지 특정한다.
--    기존 title/excerpt 컬럼은 이름 그대로 둔다: proto가 rule_name/contents로 개명했지만
--    필드 번호가 같아 담기는 값이 동일하고, 이미 API 응답으로 나가는 이름이라 바꾸지 않는다.
ALTER TABLE rule_trails ADD COLUMN IF NOT EXISTS publisher varchar(100);
ALTER TABLE rule_trails ADD COLUMN IF NOT EXISTS version integer;
ALTER TABLE rule_trails ADD COLUMN IF NOT EXISTS section_path jsonb;

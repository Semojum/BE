-- V3.1 개편으로 쓰지 않게 된 테이블 · 칼럼을 지운다 (2026-10-14 사용자 결정, 백업 없이).
--
-- 코드는 먼저 지웠다 — API #179~#183(10-13), 남은 매핑 #205(10-14). 그래서 이 마이그레이션이 도는 배포에서
-- 블루그린의 이전 색(#205 이후 코드)도 이 칼럼 · 테이블을 읽지 않는다.
--
--   jobs.folder_id · jobs.is_favorite  폴더 · 즐겨찾기 (폴더 · 작업 목록 제거)
--   folders                            V3.0 폴더
--   page_edit_histories                쪽별 수정 이력 (2026-10-12 기획 결정으로 수정 이력 폐기)
--   page_edit_logs                     저장 1번 = 1행 옛 수정 기록 (V33부터 미사용)
--
-- CASCADE를 쓰지 않는다 — 모르는 의존이 있으면 조용히 함께 지우지 않고 마이그레이션이 실패해 배포가 멈추게 한다.

ALTER TABLE jobs DROP COLUMN IF EXISTS folder_id;      -- folders를 가리키는 FK · 인덱스가 함께 빠진다
ALTER TABLE jobs DROP COLUMN IF EXISTS is_favorite;

DROP TABLE IF EXISTS folders;
DROP TABLE IF EXISTS page_edit_histories;
DROP TABLE IF EXISTS page_edit_logs;

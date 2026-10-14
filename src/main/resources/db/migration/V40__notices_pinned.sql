-- 게시판 공지 고정 (2026-10-14). 운영자 콘솔 「고정 해제」를 누르면 false가 되고 게시판 맨 위에서 내려간다.
-- 기관 관리자 공지 화면은 이 값과 상관없이 노출 기간 동안 그대로 보인다.
-- 지금 있는 공지는 전부 고정 상태(true)로 시작한다 — 지금까지 게시판에 떠 있던 그대로다.
ALTER TABLE notices ADD COLUMN IF NOT EXISTS pinned BOOLEAN NOT NULL DEFAULT TRUE;

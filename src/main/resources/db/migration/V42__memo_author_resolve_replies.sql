-- 줄 메모: 작성자 · 해결 · 답글 (2026-10-21).
--
-- 협업은 서버 공유가 아니라 .smj 파일 전달이다 — 받은 사람이 메모를 보고 답글을 달고 해결 표시를 한다.
-- 모아보기 · 해결/미해결 필터는 앱이 파일 안에서 한다. 서버는 저장 본문으로 받아 두고 쪽 조회로 돌려준다.
--
--   author       작성자 표시 이름 — 앱이 보낸 값 그대로(별칭, 없으면 로그인 ID). 파일로 넘어온 남의 메모가
--                원래 주인의 저장에 함께 올라오므로 서버가 「지금 저장하는 사람」으로 덮어쓰지 않는다
--   resolved     해결 여부 — 메모 하나(답글 포함 묶음)에 하나. 해결한 사람은 남기지 않는다(기획 결정)
--   resolved_at  해결 시각
--
-- 기존 메모는 작성자 없음(null) · 미해결로 시작한다.

ALTER TABLE page_memos ADD COLUMN IF NOT EXISTS author      varchar(100);
ALTER TABLE page_memos ADD COLUMN IF NOT EXISTS resolved    boolean NOT NULL DEFAULT false;
ALTER TABLE page_memos ADD COLUMN IF NOT EXISTS resolved_at timestamptz;

-- 답글 — 메모 하나에 여러 개. 메모처럼 쪽 단위 통째 교체라 메모를 지울 때 함께 지운다
CREATE TABLE IF NOT EXISTS page_memo_replies (
    id          bigserial     PRIMARY KEY,
    memo_id     bigint        NOT NULL,
    author      varchar(100),
    body        text          NOT NULL,
    created_at  timestamptz   NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_page_memo_replies_memo ON page_memo_replies (memo_id);

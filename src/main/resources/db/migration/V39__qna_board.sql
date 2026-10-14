-- QnA 게시판 (기획 6-2 · 피그마 D8 · D8-2, 2026-10-14).
--
-- 글 번호는 board_posts.id 그대로다(bigserial) — 지워도 번호를 당기지 않는다.
-- 공지는 따로 만들지 않고 운영자 공지(notices)를 게시판 맨 위에 보여 준다 — 조회수 칸만 더한다.
-- 삭제는 deleted_at으로 표시만 한다(답변·알림이 글을 가리키고 있다).

CREATE TABLE IF NOT EXISTS board_posts (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     UUID         NOT NULL,
    title       VARCHAR(100) NOT NULL,
    content     TEXT         NOT NULL,
    secret      BOOLEAN      NOT NULL DEFAULT FALSE,
    view_count  INT          NOT NULL DEFAULT 0,
    hidden      BOOLEAN      NOT NULL DEFAULT FALSE,   -- 운영자 숨김 — 운영자에게만 보인다
    deleted_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_board_posts_user ON board_posts (user_id);

CREATE TABLE IF NOT EXISTS board_attachments (
    id            BIGSERIAL    PRIMARY KEY,
    post_id       BIGINT       NOT NULL,
    file_name     VARCHAR(300) NOT NULL,
    content_type  VARCHAR(150),
    size_bytes    BIGINT       NOT NULL,
    storage_path  VARCHAR(400) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_board_attachments_post ON board_attachments (post_id);

-- 운영자 답변. 한 글에 여러 개 — 목록 제목 뒤 (2)가 그 수다
CREATE TABLE IF NOT EXISTS board_answers (
    id          BIGSERIAL    PRIMARY KEY,
    post_id     BIGINT       NOT NULL,
    user_id     UUID         NOT NULL,                 -- 답변을 쓴 운영자. 고치고 지우는 건 이 사람만
    content     TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_board_answers_post ON board_answers (post_id);

-- 답변 알림 — 글 하나에 하나. 「글쓴이에게 알림 보내기」를 체크한 답변만 만들거나 다시 안 읽음으로 돌린다
CREATE TABLE IF NOT EXISTS board_notifications (
    id                BIGSERIAL    PRIMARY KEY,
    user_id           UUID         NOT NULL,
    post_id           BIGINT       NOT NULL UNIQUE,
    last_answered_at  TIMESTAMPTZ  NOT NULL,
    is_read           BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_board_notifications_user ON board_notifications (user_id);

ALTER TABLE notices ADD COLUMN IF NOT EXISTS view_count INT NOT NULL DEFAULT 0;

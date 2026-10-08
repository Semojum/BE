-- page_edit_histories: 수정 이력을 쪽당 1행으로 (2026-10-08).
--
-- page_edit_logs는 저장 1번 = 1행이라 같은 쪽을 고칠수록 행이 쌓였다. 기획 확정 구조는 쪽마다 한 행에
--   원본 쪽 · AI 초안(텍스트·점자) · 이전 수정 · 최근 수정 — 을 두고, 저장하면 최근 → 이전으로 밀고 새 값을 최근에 넣는다.
-- 처음 수정하면 이전 수정 = AI 초안(직전 → 최근 비교가 항상 "무엇을 고쳤나"가 되도록).
--
-- 행은 쪽 번호가 아니라 pages.id로 찾는다 — 원본 쪽 삭제는 뒤 쪽 번호를 당기므로(page_no 키면 엉뚱한 쪽 행을 덮는다).
-- FK는 걸지 않는다: 원본 쪽을 영구 삭제해도 편집 이력은 RLHF 자료로 남긴다.
--
-- page_edit_logs는 그대로 둔다(공유 RDS · 블루그린 공존 — 구버전이 잠시 계속 쓴다). 이 마이그레이션 이후 신규 저장은 여기로만.

CREATE TABLE IF NOT EXISTS page_edit_histories (
    id              uuid PRIMARY KEY,
    page_id         uuid        NOT NULL UNIQUE,
    job_id          varchar(255) NOT NULL,
    page_no         int         NOT NULL,          -- 마지막 저장 시점의 쪽 번호(기록용 — 삭제로 당겨져도 갱신 안 함)
    user_id         uuid        NOT NULL,          -- 마지막으로 저장한 사용자
    mode            varchar(8)  NOT NULL,

    -- 원본 쪽: a/c = PDF 경로 + 이미지 크기(블록별 bbox는 스냅샷 안), b = 원문 텍스트
    source_pdf_path varchar(1024),
    image_width     int,
    image_height    int,
    source_text     text,

    -- 각 칸은 쪽 패널 전체 [{id, type, heading_level, contents, origin, ai_original, bounding_box}] 읽기 순서대로.
    -- 그 모드에 없는 패널은 null(b의 텍스트는 source_text, 구 mode a엔 점자 없음)
    ai_text         jsonb,
    ai_braille      jsonb,
    prev_text       jsonb,
    prev_braille    jsonb,
    latest_text     jsonb,
    latest_braille  jsonb,

    edited_panel    varchar(16),                   -- 최근 저장에서 직접 고친 패널: text | braille
    changed         jsonb,                         -- 최근 저장의 diff 요약 (page_edit_logs.changed와 같은 모양)
    edit_count      int         NOT NULL DEFAULT 0,

    created_at      timestamptz NOT NULL,          -- 첫 수정
    updated_at      timestamptz NOT NULL           -- 최근 수정
);

CREATE INDEX IF NOT EXISTS idx_page_edit_histories_job ON page_edit_histories (job_id);

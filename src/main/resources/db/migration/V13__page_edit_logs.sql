-- 페이지 일괄 저장 이력(RLHF 학습용) — 1저장 = 1행, 페이지 전체 before/after 스냅샷.
-- 요소 단위 edit_logs를 대체한다: 블록 추가·이동처럼 페이지 맥락이 필요한 편집을 요소 행으로는 담을 수 없었다.
CREATE TABLE page_edit_logs (
    id              uuid PRIMARY KEY,
    user_id         uuid         NOT NULL,
    job_id          varchar(255) NOT NULL,
    page_no         int          NOT NULL,
    mode            varchar(255) NOT NULL,
    element_type    varchar(255) NOT NULL,
    before_elements jsonb        NOT NULL,
    after_elements  jsonb        NOT NULL,
    changed         jsonb        NOT NULL,
    source_pdf_path varchar(255),
    image_width     int,
    image_height    int,
    source_text     text,
    created_at      timestamptz  NOT NULL
);

CREATE INDEX idx_page_edit_logs_job_page ON page_edit_logs (job_id, page_no);

-- 요소 단위 편집 API 제거와 함께 기존 edit_logs 폐기 (팀 결정 2026-08-06)
DROP TABLE IF EXISTS edit_logs;

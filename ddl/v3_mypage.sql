-- V3 마이페이지 디렉토리 구조 (배포 전 수동 실행 — ddl-auto:none)
-- 근거: [세모점 V3] 기능정의서 — BE 3장 / [세모점 V3] API 명세서 Folder·Trash·Job

-- 1) folders 신규
CREATE TABLE IF NOT EXISTS folders (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          uuid NOT NULL REFERENCES users(id),
    parent_folder_id uuid REFERENCES folders(id),
    name             varchar(50) NOT NULL,
    deleted_at       timestamptz,          -- 값이 있으면 휴지통 (30일 후 완전 삭제)
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_folders_user ON folders(user_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_folders_parent ON folders(parent_folder_id) WHERE deleted_at IS NULL;

-- 2) jobs 컬럼 추가
-- work_name: 작업 이름(기본값 = 원본 파일명). 원본 파일명과 분리해 사용자 변경 가능
-- folder_id: NULL = 루트
-- deleted_at: 휴지통 (folders와 동일 정책)
-- last_modified_at: 카드 날짜·정렬 기준. updated_at(변환 파이프라인 전용, StaleJobScheduler 판정용)과 반드시 분리
-- last_edited_page / is_edited: 페이지 일괄 저장 API(4장)용 — 컬럼만 선반영
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS work_name        varchar(100);
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS folder_id        uuid REFERENCES folders(id);
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS deleted_at       timestamptz;
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS last_modified_at timestamptz;
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS last_edited_page integer;
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS is_edited        boolean NOT NULL DEFAULT false;

-- 백필: 기존 행은 원본 파일명을 작업 이름으로, started_at(없으면 now())을 카드 날짜로
UPDATE jobs SET work_name = original_file_name WHERE work_name IS NULL;
UPDATE jobs SET last_modified_at = COALESCE(finished_at, started_at, now()) WHERE last_modified_at IS NULL;

ALTER TABLE jobs ALTER COLUMN work_name SET NOT NULL;
ALTER TABLE jobs ALTER COLUMN last_modified_at SET NOT NULL;
ALTER TABLE jobs ALTER COLUMN last_modified_at SET DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_jobs_user_folder ON jobs(user_id, folder_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_jobs_user_modified ON jobs(user_id, last_modified_at DESC) WHERE deleted_at IS NULL;

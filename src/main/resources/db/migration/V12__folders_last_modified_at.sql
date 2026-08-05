-- 폴더에도 "수정한 날짜"를 둔다.
--
-- 그동안 폴더 정렬 기준이 created_at이라, 폴더 안에서 아무리 작업해도 순서가 고정이었다.
-- 최신순 정렬이 의미를 가지려면 폴더 안의 활동이 반영돼야 한다.
--
-- 갱신 시점: 직속 항목의 추가·삭제·이름변경(윈도우 탐색기와 동일) +
--            직속 파일의 내용 편집(점역사의 주된 활동이므로 포함)
-- 상위 폴더로 전파하지 않는다 — 직속 폴더만 갱신한다.

ALTER TABLE folders ADD COLUMN IF NOT EXISTS last_modified_at timestamptz;

-- 기존 폴더는 생성 시각을 출발점으로
UPDATE folders SET last_modified_at = created_at WHERE last_modified_at IS NULL;

-- DEFAULT가 있어야 컬럼을 생략하는 기존 INSERT와도 호환된다
ALTER TABLE folders ALTER COLUMN last_modified_at SET DEFAULT now();
ALTER TABLE folders ALTER COLUMN last_modified_at SET NOT NULL;

-- 목록 정렬용
CREATE INDEX IF NOT EXISTS idx_folders_user_modified
    ON folders (user_id, last_modified_at DESC, id DESC)
    WHERE deleted_at IS NULL;

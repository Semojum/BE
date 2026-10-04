-- page_edit_logs: 한 행에 쪽의 텍스트·점자 패널을 둘 다 스냅샷한다 (3패널, 2026-10-04).
--
-- 3패널(mode c)에선 텍스트를 저장하면 같은 id 점자가 재점역돼 함께 바뀐다. 종전 구조(before/after_elements +
-- element_type)는 한 행에 한 패널만 담아, 따라 바뀐 점자는 changed.braille_synced의 id만 남고 내용이 없었다.
-- 이제 저장 1번 = 1행에 두 패널의 전·후를 모두 담는다. 블록마다 ai_original(AI 원본)은 종전처럼 포함.
-- 패널이 없는 모드는 null — TXT(mode b)의 텍스트는 원문이라 source_text에 있고, 구 mode a엔 점자가 없다.
--
-- 이 마이그레이션 이전 행은 새 컬럼이 비어 있고 구 컬럼(before/after_elements)에만 내용이 있다.
-- edited_panel IS NULL 이면 구 구조 행이다.

ALTER TABLE page_edit_logs
    ADD COLUMN IF NOT EXISTS before_text    jsonb,
    ADD COLUMN IF NOT EXISTS after_text     jsonb,
    ADD COLUMN IF NOT EXISTS before_braille jsonb,
    ADD COLUMN IF NOT EXISTS after_braille  jsonb,
    -- 사용자가 직접 고친 패널: text | braille (구 element_type TEXT | BRAILLE을 대체)
    ADD COLUMN IF NOT EXISTS edited_panel   varchar(16);

-- 구 컬럼은 이번엔 지우지 않고 NOT NULL만 푼다. RDS 하나를 로컬·개발·운영이 공유하고 블루그린 전환 중엔
-- 구버전이 잠시 함께 떠 있어, 구 코드가 이 테이블에 써도 깨지지 않아야 한다. 삭제는 다음 마이그레이션에서.
ALTER TABLE page_edit_logs
    ALTER COLUMN element_type    DROP NOT NULL,
    ALTER COLUMN before_elements DROP NOT NULL,
    ALTER COLUMN after_elements  DROP NOT NULL;

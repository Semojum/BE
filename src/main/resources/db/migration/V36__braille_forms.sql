-- braille_forms: 점역 양식 (D7 「점역 양식」 · D7-2 「사용자 양식 설정」, 2026-10-14).
--
-- 양식 하나가 조판 옵션 + 점역 옵션 한 벌을 묶어 둔 것이다. 유형별로 자주 쓰는 설정을 저장해 두고
-- 업로드할 때 골라 쓴다 — 고르면 그 값이 작업으로 복사된다(양식을 나중에 고쳐도 기존 작업은 불변).
--
-- 두 칸을 jobs.layout_options · users.braille_options와 같은 모양으로 둔다. 양식 적용이 통째 복사
-- 한 줄로 끝나고, 조판 옵션에 항목이 늘어도 양식이 저절로 따라온다 — 번역표를 만들면 조용히 어긋난다.
--
-- 기본 양식 5종(초등 교과서·중고 교과서·자습서·시험지·일반 도서)은 이 표에 없다. 클래스패스 리소스로
-- 두고 코드와 함께 배포한다 — 점자 규정(braille-rules.json)과 같은 이유다. 세 환경이 RDS 하나를
-- 공유하므로 DB에 넣으면 배포를 되돌려도 양식만 새 버전으로 남는다.
--
-- 이름은 계정 안에서 유일하다 — 목록에서 고르는 것이 이름뿐이라 같은 이름 둘은 구분할 수 없다.

CREATE TABLE IF NOT EXISTS braille_forms (
    id              uuid         PRIMARY KEY,
    user_id         uuid         NOT NULL,
    name            varchar(100) NOT NULL,
    base_form_id    varchar(64),              -- 바탕 양식(builtin:XXX 또는 UUID). null = 바탕 없음
    layout_options  jsonb        NOT NULL,
    braille_options jsonb        NOT NULL,
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_braille_forms_user ON braille_forms (user_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_braille_forms_user_name ON braille_forms (user_id, name);

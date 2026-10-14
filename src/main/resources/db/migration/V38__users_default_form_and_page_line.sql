-- users: D6 「기본 점역 옵션 설정」에만 있는 계정 기본값 두 개 (2026-10-14).
--
-- D6 화면에는 점역 옵션(users.braille_options) 말고 둘이 더 있다.
--   default_form_id           점역 양식 드롭다운 — 「열기」 창에서 처음 골라져 있을 양식. builtin:XXX 또는 사용자 양식 UUID
--   default_page_number_line  페이지행 면(홀수 · 모든) — 조판 옵션 pageNumberLine의 계정 기본값
--
-- braille_options(jsonb)에 넣지 않은 이유: 그 묶음은 양식·작업으로 통째 복사된다. 페이지행 면은 이미
-- 조판 옵션(layout_options.pageNumberLine)에 있어서 거기 또 넣으면 작업·양식마다 같은 값이 두 군데 생긴다.
-- 기본 양식도 계정에만 의미가 있다(작업은 값을 복사해 가므로 어느 양식이었는지 들고 다닐 이유가 없다).
--
-- null = 저장한 적 없음 → 조회 시 공장 기본값(중·고 교과서 · 홀수).

ALTER TABLE users ADD COLUMN IF NOT EXISTS default_form_id varchar(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS default_page_number_line varchar(8);

-- jobs.braille_options: 그 작업에 적용된 점역 옵션 (2026-10-14).
--
-- 업로드 때 계정 기본값(users.braille_options) 또는 고른 양식(braille_forms)에서 복사해 넣는다.
-- 참조가 아니라 사본인 이유: 기본값이나 양식을 나중에 고쳐도, 양식을 지워도, 이미 만든 작업의
-- 설정은 그대로여야 한다. 작업은 그때의 설정으로 점역된 결과물이기 때문이다.
--
-- layout_options(V30)와 같은 자리·같은 방식이다. 한 칸(jsonb)이라 항목이 바뀌어도 마이그레이션이
-- 생기지 않고, 모르는 필드는 읽는 쪽이 무시한다.
--
-- null = V3.1 이전에 만든 작업 → 조회 시 공장 기본값으로 채운다.

ALTER TABLE jobs ADD COLUMN IF NOT EXISTS braille_options jsonb;

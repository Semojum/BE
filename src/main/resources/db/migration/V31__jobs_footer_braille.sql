-- 꼬리말의 점역 결과를 저장한다 (FE 요청 S-4, 2026-09-03).
--
-- 종전엔 다운로드하는 순간에만 AI TranslateText로 점역해 썼다. 그래서 에디터 화면은
-- 점역된 꼬리말을 볼 방법이 없어 페이지행의 꼬리말 자리가 늘 빈칸이었고(파일에는 정상),
-- 꼬리말 정렬 같은 조판 옵션을 화면에서 확인할 수 없었다.
--
-- footer_text는 업로드 때만 정해지고 수정 경로가 없으므로(2026-09-03 확인) 한 번 점역해
-- 두면 무효화 걱정이 없다. null = 아직 점역 못 했음(꼬리말이 없거나 AI 호출이 실패한 경우)
-- → 읽는 쪽에서 필요할 때 다시 점역해 채운다.
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS footer_braille varchar(400);

COMMENT ON COLUMN jobs.footer_braille IS
    '꼬리말(footer_text)의 점역 결과. null이면 미점역 — 조회 시점에 채운다';

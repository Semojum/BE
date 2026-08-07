-- 꼬리말(묵자) — Job 생성 시 점역사가 입력. 다운로드(brf 조판) 때 TranslateText로 점역해 페이지행 가운데에 배치.
-- 200자 제한은 AI TranslateText RPC의 입력 상한과 동일.
ALTER TABLE jobs ADD COLUMN footer_text varchar(200);

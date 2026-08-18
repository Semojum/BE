-- V19: 접속 메타데이터 수집 (T1-4 작업 상세 "요청 정보" — 오류 문의 시 환경 재현 근거)
-- 위치는 저장하지 않는다 — IP만 있으면 표시 시점에 GeoIP로 과거 작업까지 위치 조회 가능
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS client_ip         VARCHAR(45);   -- IPv6 최대 45자
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS client_os         VARCHAR(50);   -- 파싱값 (예: "Windows")
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS client_browser    VARCHAR(80);   -- 파싱값 (예: "Chrome 141")
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS client_user_agent VARCHAR(300);  -- 원본 UA (파싱 규칙이 바뀌어도 재해석 가능)

-- V28 (2026-08-21): 웹/앱 관리자 분리 + 관리자 사본 표식 + 문의 인라인 이미지 구분
-- 공유 DB 안전성: 세 컬럼 모두 기본값이 기존 동작과 동일(null/false) — 구버전 코드가 몰라도 무해

-- ROLE_ADMIN 계정의 사용처 구분: WEB(운영자 콘솔 전용 — 등록 기기(MAC)에서만 로그인, 앱 로그인 불가)
--                              APP(에디터 앱용 — 웹 관리자의 "마이페이지로 보내기" 수신 대상)
-- null = 기존 관리자 계정(verify01 등), 종전과 동일하게 동작
ALTER TABLE users ADD COLUMN IF NOT EXISTS admin_scope varchar(10);

-- 관리자 사본(send-to-mypage) 표식 — 실제 변환이 아니므로 통계(건수·쪽수·원가)에서 제외
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS admin_copy boolean NOT NULL DEFAULT false;

-- 메일 본문 인라인 이미지 구분 — 문의 상세 응답에서 presigned URL을 즉시 실어 바로 렌더
ALTER TABLE inquiry_attachments ADD COLUMN IF NOT EXISTS is_inline boolean NOT NULL DEFAULT false;
-- 기존 데이터 백필: disposition을 저장하지 않았으므로 이미지 = 인라인으로 근사 (표시 UX 우선)
UPDATE inquiry_attachments SET is_inline = true WHERE content_type LIKE 'image/%';

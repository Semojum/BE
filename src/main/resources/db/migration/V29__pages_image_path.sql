-- V29 (2026-08-31): 원본 페이지 미리보기용 이미지 경로
-- FE가 원본 좌측 패널을 pdf.js로 그리던 걸 이미지 <img>로 바꾸기 위한 것.
-- 실측(2026-08-31): 같은 화면을 그리는 데 PDF 경로는 스캔본 1,807~2,853ms(JPEG 2000은 브라우저에
-- 네이티브 디코더가 없어 pdf.js가 WASM으로 직접 푼다) / JPEG 경로는 6~9ms.
-- null = 아직 생성 전이거나 렌더 실패 — 이때는 기존처럼 원본 PDF를 그대로 내려준다(폴백).
-- 공유 DB 안전성: nullable 컬럼 추가라 구버전 코드가 몰라도 무해
ALTER TABLE pages ADD COLUMN IF NOT EXISTS image_path varchar(255);

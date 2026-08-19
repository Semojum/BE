-- V26: 앱 버전 관리 — 데스크톱 앱이 시작 시 조회해 강제/권장 업데이트 판단 (피그마 V3-05 업데이트 화면)
-- 갱신 = 새 행 추가 (단가표와 같은 이력 보존 패턴). 조회는 최신 행
CREATE TABLE app_versions (
    id bigserial PRIMARY KEY,
    latest_version varchar(20) NOT NULL,          -- 최신 배포 버전 (예: 1.2.0)
    min_supported_version varchar(20) NOT NULL,   -- 이 미만은 강제 업데이트
    download_url varchar(500),                    -- 설치 파일 위치
    release_notes text,                           -- 업데이트 안내 문구
    note varchar(500),                            -- 운영 메모 (변경 사유)
    created_at timestamptz NOT NULL DEFAULT now()
);

-- V3 인증·계정 개편 DDL (ddl-auto:none — 배포 전 수동 실행)
-- 대상: RDS semojum-postgres (postgres DB)

-- 1. 기관 테이블
CREATE TABLE organizations (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name                varchar(255) NOT NULL,
    contract_expires_at date,
    status              varchar(20) NOT NULL DEFAULT 'ACTIVE',
    created_at          timestamp NOT NULL DEFAULT now()
);

-- 2. users에 발급형 계정 컬럼 추가 (레거시 소셜/이메일 행은 null 유지 — 로그인 불가 상태로 남음)
ALTER TABLE users ADD COLUMN login_id varchar(100) UNIQUE;
ALTER TABLE users ADD COLUMN organization_id uuid REFERENCES organizations (id);

-- 참고:
-- * email/provider/provider_uid 컬럼은 유지 (레거시 데이터 보존, 코드에서는 미사용)
-- * user_sessions는 스키마 변경 없음 — 중복 로그인 금지는 애플리케이션(revokeAllActiveByUser)에서 처리

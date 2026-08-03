-- V3 사용자 모델 정리
-- 1) 레거시 사용자(login_id IS NULL — 이메일/소셜 시절 계정)와 그 작업 데이터 전체 삭제
-- 2) 소셜 로그인·프로필 컬럼(email, name, provider, provider_uid) 제거
-- 3) V3 발급 계정 필수값 강제(login_id NOT NULL)
-- 삭제 순서는 TrashPurgeRepository와 동일 (FK 자식 테이블부터)

CREATE TEMP TABLE legacy_users AS
    SELECT id FROM users WHERE login_id IS NULL;
CREATE TEMP TABLE legacy_jobs AS
    SELECT id FROM jobs WHERE user_id IN (SELECT id FROM legacy_users);

DELETE FROM rule_trails WHERE element_id IN (
    SELECT id FROM text_elements
    WHERE page_result_id IN (SELECT id FROM page_results WHERE job_id IN (SELECT id FROM legacy_jobs))
    UNION
    SELECT id FROM braille_elements
    WHERE page_result_id IN (SELECT id FROM page_results WHERE job_id IN (SELECT id FROM legacy_jobs))
);
DELETE FROM text_elements
    WHERE page_result_id IN (SELECT id FROM page_results WHERE job_id IN (SELECT id FROM legacy_jobs));
DELETE FROM braille_elements
    WHERE page_result_id IN (SELECT id FROM page_results WHERE job_id IN (SELECT id FROM legacy_jobs));
DELETE FROM bounding_boxes
    WHERE page_result_id IN (SELECT id FROM page_results WHERE job_id IN (SELECT id FROM legacy_jobs));
DELETE FROM quality_critical_errors
    WHERE page_result_id IN (SELECT id FROM page_results WHERE job_id IN (SELECT id FROM legacy_jobs));
DELETE FROM quality_review_flags
    WHERE page_result_id IN (SELECT id FROM page_results WHERE job_id IN (SELECT id FROM legacy_jobs));
DELETE FROM page_results WHERE job_id IN (SELECT id FROM legacy_jobs);
DELETE FROM pages WHERE job_id IN (SELECT id FROM legacy_jobs);
DELETE FROM edit_logs
    WHERE job_id IN (SELECT id FROM legacy_jobs)
       OR user_id IN (SELECT id FROM legacy_users);
DELETE FROM jobs WHERE id IN (SELECT id FROM legacy_jobs);
DELETE FROM user_sessions WHERE user_id IN (SELECT id FROM legacy_users);
DELETE FROM folders WHERE user_id IN (SELECT id FROM legacy_users);
DELETE FROM users WHERE id IN (SELECT id FROM legacy_users);

DROP TABLE legacy_jobs;
DROP TABLE legacy_users;

ALTER TABLE users DROP COLUMN IF EXISTS email;
ALTER TABLE users DROP COLUMN IF EXISTS name;
ALTER TABLE users DROP COLUMN IF EXISTS provider;
ALTER TABLE users DROP COLUMN IF EXISTS provider_uid;

ALTER TABLE users ALTER COLUMN login_id SET NOT NULL;

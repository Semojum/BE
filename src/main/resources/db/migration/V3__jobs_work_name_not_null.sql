-- V3 마이페이지 2단계 — work_name NOT NULL
-- 이 마이그레이션은 V3 코드(항상 work_name을 넣는 코드)와 "함께" 배포되므로,
-- 앱 기동 시점에 적용되어도 이후 INSERT가 깨지지 않는다.
-- 배포 사이에 V2 코드가 만든 행이 있을 수 있으니 백필을 한 번 더 하고 적용한다.

UPDATE jobs SET work_name = original_file_name WHERE work_name IS NULL;
UPDATE jobs SET work_name = '이름 없는 작업' WHERE work_name IS NULL; -- original_file_name도 NULL인 극단 케이스 방어

ALTER TABLE jobs ALTER COLUMN work_name SET NOT NULL;

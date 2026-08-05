-- 모든 시각 컬럼을 timestamptz(절대시각)로 통일한다.
--
-- 기존에는 timestamp without time zone과 timestamptz가 섞여 있어, 앱/DB 세션 시간대가
-- 바뀌면 같은 테이블 안에서도 값이 어긋났다(실제로 컨테이너가 UTC로 돌던 구간에 9시간 오차 발생).
-- timestamptz로 두면 저장값이 절대시각이라 시간대 설정과 무관하게 정확하다.
--
-- 기존 값은 한국 시간(KST) 벽시계로 기록돼 있으므로 그렇게 해석해 변환한다.

ALTER TABLE users          ALTER COLUMN created_at  TYPE timestamptz USING created_at  AT TIME ZONE 'Asia/Seoul';
ALTER TABLE organizations  ALTER COLUMN created_at  TYPE timestamptz USING created_at  AT TIME ZONE 'Asia/Seoul';

ALTER TABLE user_sessions  ALTER COLUMN created_at  TYPE timestamptz USING created_at  AT TIME ZONE 'Asia/Seoul';
ALTER TABLE user_sessions  ALTER COLUMN expires_at  TYPE timestamptz USING expires_at  AT TIME ZONE 'Asia/Seoul';
ALTER TABLE user_sessions  ALTER COLUMN revoked_at  TYPE timestamptz USING revoked_at  AT TIME ZONE 'Asia/Seoul';

ALTER TABLE jobs           ALTER COLUMN started_at  TYPE timestamptz USING started_at  AT TIME ZONE 'Asia/Seoul';
ALTER TABLE jobs           ALTER COLUMN finished_at TYPE timestamptz USING finished_at AT TIME ZONE 'Asia/Seoul';

ALTER TABLE pages          ALTER COLUMN created_at  TYPE timestamptz USING created_at  AT TIME ZONE 'Asia/Seoul';
ALTER TABLE page_results   ALTER COLUMN created_at  TYPE timestamptz USING created_at  AT TIME ZONE 'Asia/Seoul';
ALTER TABLE edit_logs      ALTER COLUMN created_at  TYPE timestamptz USING created_at  AT TIME ZONE 'Asia/Seoul';

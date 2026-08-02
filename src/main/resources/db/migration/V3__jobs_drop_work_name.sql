-- 팀 결정(2026-08-03): 파일 하나 = 이름 하나. 표시용 이름(work_name)을 분리하지 않고
-- original_file_name 자체를 카드 표시·이름 변경 대상으로 사용한다.
-- V2__에서 추가한 work_name 컬럼을 제거한다. (V2__는 이미 적용·기록되어 수정 불가 → 새 마이그레이션으로 정리)
-- 운영 V2 코드는 work_name을 참조하지 않으므로 언제 적용돼도 안전하다.

ALTER TABLE jobs DROP COLUMN IF EXISTS work_name;

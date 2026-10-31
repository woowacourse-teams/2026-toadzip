-- 외부 수집 실패도 다른 수집·정제 실패와 같은 공통 실패 매핑을 쓰도록 관리자 해결자 열을 맞춘다.
-- 외부 수집 실패는 관리자가 직접 해결하지 않으므로 기존 행과 새 행 모두 NULL이다. backfill은 하지 않는다.
-- 이 마이그레이션을 먼저 적용하고 애플리케이션을 배포한다. 이전 버전 애플리케이션은 이 열을 읽지 않는다.
-- 되돌릴 때는 새 애플리케이션을 내린 뒤 열을 지우면 된다(ALTER TABLE ... DROP COLUMN last_resolved_by).
ALTER TABLE external_data_collection_failures ADD COLUMN last_resolved_by varchar(255);

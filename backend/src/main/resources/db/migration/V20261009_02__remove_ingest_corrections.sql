-- 일반 보완·매칭 규칙은 더 이상 저장하지 않는다. 기존 제품·원천·실행 이력은 보존한다.
-- V20261008_02의 적용 여부와 무관하게 전진 마이그레이션으로 정리한다.
-- 이전 애플리케이션을 중단하고, 필요하면 보완·매칭 정보를 백업한 뒤 적용한다.
DROP TABLE IF EXISTS announcement_supply_matches;
DROP TABLE IF EXISTS ingest_correction_changes;
DROP TABLE IF EXISTS ingest_corrections;
ALTER TABLE housing_types DROP COLUMN IF EXISTS admin_correction;

-- 기존 수집 데이터는 재수집한다. 제품·관리자 데이터와 실행/실패 감사 이력은 보존한다.
-- 배포 시 수집/정제 작업을 중지한 상태에서 적용한다. 식별자 sequence는 재설정하지 않는다.
DELETE FROM myhome_announcement_source_rows;
DELETE FROM myhome_complex_source_rows;
DELETE FROM lh_lease_catalog_source_rows;
DELETE FROM lh_announcement_supply_rows;
DELETE FROM lh_announcement_detail_rows;
DELETE FROM lh_announcement_query_parameters;
DELETE FROM myhome_announcement_source_bundles;
DELETE FROM myhome_complex_source_bundles;
DELETE FROM myhome_complex_source_regions;
DELETE FROM lh_lease_catalog_source_bundles;
DELETE FROM lh_announcement_catalog_entries;
DELETE FROM lh_announcement_query_sources;
DELETE FROM lh_announcement_collection_links;
DELETE FROM myhome_announcement_lifecycle_runs;

-- 폐기된 이전 원천에 대한 승인이 새 수집 데이터에 재사용되지 않게 한다.
UPDATE verified_lh_supply_replacements
SET revoked_at = CURRENT_TIMESTAMP
WHERE consumed_at IS NULL AND revoked_at IS NULL;

UPDATE external_data_collection_failures
SET status = 'SKIPPED', resolved_at = CURRENT_TIMESTAMP
WHERE source = 'LH_ANNOUNCEMENT_SUPPLY' AND status = 'PENDING'
  AND error_type IN ('IncompleteLhSupplyReplacementException', 'EmptyLhSupplyReplacementException');

DROP TABLE myhome_complex_source;
DROP TABLE myhome_announcement_source;
DROP TABLE lh_catalog_source;
DROP TABLE lh_announcement_catalog_source;
DROP TABLE lh_announcement_detail_source;
DROP TABLE lh_announcement_supply_source;
DROP TABLE lh_announcement_collection_checkpoints;
DROP TABLE source_legacy_import_report;
-- 이전 로컬 수집 구현에서 별도 migration으로 생성했던 제어 테이블도 제거한다.
DROP TABLE IF EXISTS myhome_announcement_collection_runs;

ALTER TABLE source_collection_records DROP CONSTRAINT source_collection_records_status_check;
ALTER TABLE source_collection_records DROP CONSTRAINT source_collection_records_check1;
ALTER TABLE source_collection_records ADD CONSTRAINT ck_source_collection_record_status
    CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'IMPORTED'));
ALTER TABLE source_collection_records ADD CONSTRAINT ck_source_collection_record_completion
    CHECK ((status = 'RUNNING' AND finished_at IS NULL AND stored_row_count = 0
              AND error_type IS NULL AND failure_reason IS NULL)
        OR (status IN ('SUCCESS', 'IMPORTED') AND finished_at IS NOT NULL
              AND error_type IS NULL AND failure_reason IS NULL)
        OR (status = 'FAILED' AND finished_at IS NOT NULL AND stored_row_count = 0
              AND error_type IS NOT NULL AND failure_reason IS NOT NULL));

ALTER TABLE myhome_announcement_source_rows ALTER COLUMN collected_at DROP NOT NULL;
ALTER TABLE myhome_announcement_source_rows ALTER COLUMN request_supply_type_code DROP NOT NULL;
ALTER TABLE myhome_announcement_source_rows ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE myhome_announcement_source_rows ADD COLUMN consecutive_miss_count INTEGER NOT NULL DEFAULT 0
    CHECK (consecutive_miss_count >= 0);
ALTER TABLE myhome_announcement_source_rows ADD COLUMN last_seen_run_id VARCHAR(100);
CREATE TABLE myhome_announcement_lifecycle_runs (
    execution_id UUID PRIMARY KEY,
    completed_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_source_collection_records_execution_source ON source_collection_records(execution_id, source);

ALTER TABLE IF EXISTS myhome_complex_source_regions ALTER COLUMN collected_at DROP NOT NULL;
ALTER TABLE IF EXISTS myhome_complex_source_rows ADD COLUMN collected_at TIMESTAMPTZ;
ALTER TABLE IF EXISTS lh_lease_catalog_source_bundles ALTER COLUMN collected_at DROP NOT NULL;
ALTER TABLE IF EXISTS lh_lease_catalog_source_rows ADD COLUMN collected_at TIMESTAMPTZ;
ALTER TABLE IF EXISTS lh_announcement_query_sources ALTER COLUMN collected_at DROP NOT NULL;
ALTER TABLE IF EXISTS lh_announcement_supply_rows ADD COLUMN collected_at TIMESTAMPTZ;
ALTER TABLE IF EXISTS lh_announcement_detail_rows ADD COLUMN collected_at TIMESTAMPTZ;

UPDATE myhome_announcement_source_rows rows
SET last_seen_run_id = COALESCE(records.execution_id::text, records.id::text)
FROM source_collection_records records WHERE records.id = rows.collection_record_id;
DO $$
BEGIN
    IF to_regclass('myhome_complex_source_rows') IS NOT NULL THEN
        UPDATE myhome_complex_source_rows rows SET collected_at = regions.collected_at
        FROM myhome_complex_source_bundles bundles JOIN myhome_complex_source_regions regions
            ON regions.id = bundles.region_id WHERE rows.source_id = bundles.id;
    END IF;
    IF to_regclass('lh_lease_catalog_source_rows') IS NOT NULL THEN
        UPDATE lh_lease_catalog_source_rows rows SET collected_at = sources.collected_at
        FROM lh_lease_catalog_source_bundles sources WHERE rows.source_id = sources.id;
    END IF;
    IF to_regclass('lh_announcement_supply_rows') IS NOT NULL THEN
        UPDATE lh_announcement_supply_rows rows SET collected_at = sources.collected_at
        FROM lh_announcement_query_sources sources WHERE rows.source_id = sources.id;
        UPDATE lh_announcement_detail_rows rows SET collected_at = sources.collected_at
        FROM lh_announcement_query_sources sources WHERE rows.source_id = sources.id;
    END IF;
END $$;

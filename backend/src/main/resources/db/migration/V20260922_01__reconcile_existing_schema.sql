-- Bring the known production schema snapshot to the current application schema.
-- Existing databases must first be explicitly baselined at 20260922.00.

-- Applied state from V20260902_01__add_myhome_announcement_source_lifecycle.sql

ALTER TABLE myhome_announcement_source
    ADD COLUMN IF NOT EXISTS last_seen_run_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS consecutive_miss_count INTEGER,
    ADD COLUMN IF NOT EXISTS active BOOLEAN;

UPDATE myhome_announcement_source
SET consecutive_miss_count = COALESCE(consecutive_miss_count, 0),
    active = COALESCE(active, TRUE);

ALTER TABLE myhome_announcement_source
    ALTER COLUMN consecutive_miss_count SET DEFAULT 0,
    ALTER COLUMN consecutive_miss_count SET NOT NULL,
    ALTER COLUMN active SET DEFAULT TRUE,
    ALTER COLUMN active SET NOT NULL;

-- Applied state from V20260903_01__create_data_pipeline_executions.sql

CREATE TABLE IF NOT EXISTS data_pipeline_executions (
    id BIGSERIAL PRIMARY KEY,
    execution_id UUID NOT NULL UNIQUE,
    type VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    current_step VARCHAR(60),
    failed_step VARCHAR(60),
    failure_message VARCHAR(500),
    failure_server_response TEXT,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    heartbeat_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE
);

ALTER TABLE data_pipeline_executions
    ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE data_pipeline_executions
    ALTER COLUMN type TYPE VARCHAR(40);

UPDATE data_pipeline_executions
SET heartbeat_at = COALESCE(heartbeat_at, started_at);

ALTER TABLE data_pipeline_executions
    ALTER COLUMN heartbeat_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_data_pipeline_execution_type_id
    ON data_pipeline_executions (type, id);

CREATE TABLE IF NOT EXISTS data_pipeline_execution_completed_steps (
    data_pipeline_execution_id BIGINT NOT NULL,
    step_order INTEGER NOT NULL,
    completed_step VARCHAR(60) NOT NULL,
    PRIMARY KEY (data_pipeline_execution_id, step_order),
    CONSTRAINT fk_data_pipeline_completed_step_execution
        FOREIGN KEY (data_pipeline_execution_id)
        REFERENCES data_pipeline_executions (id)
        ON DELETE CASCADE
);

-- Applied state from V20260903_02__add_data_pipeline_skipped_steps.sql

CREATE TABLE IF NOT EXISTS data_pipeline_execution_skipped_steps (
    data_pipeline_execution_id BIGINT NOT NULL,
    step_order INTEGER NOT NULL,
    skipped_step VARCHAR(60) NOT NULL,
    skip_reason VARCHAR(500) NOT NULL,
    skip_server_response TEXT,
    PRIMARY KEY (data_pipeline_execution_id, step_order),
    CONSTRAINT fk_data_pipeline_skipped_step_execution
        FOREIGN KEY (data_pipeline_execution_id)
        REFERENCES data_pipeline_executions (id)
        ON DELETE CASCADE
);

-- Applied state from V20260903_03__create_road_address_locations.sql

CREATE TABLE IF NOT EXISTS road_address_locations (
    road_name_code VARCHAR(12) NOT NULL,
    underground VARCHAR(1) NOT NULL,
    building_main_number INTEGER NOT NULL,
    building_sub_number INTEGER NOT NULL,
    entrance_serial VARCHAR(10) NOT NULL,
    province_code VARCHAR(2) NOT NULL,
    road_address VARCHAR(500) NOT NULL,
    normalized_road_address VARCHAR(500) NOT NULL,
    x NUMERIC(15, 6),
    y NUMERIC(15, 6),
    PRIMARY KEY (
        road_name_code,
        underground,
        building_main_number,
        building_sub_number,
        entrance_serial
    ),
    CONSTRAINT ck_road_address_location_coordinate_pair
        CHECK ((x IS NULL AND y IS NULL) OR (x IS NOT NULL AND y IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_road_address_location_address
    ON road_address_locations (normalized_road_address);

-- Applied state from V20260911_01__create_lh_announcement_collection_links.sql

CREATE TABLE IF NOT EXISTS lh_announcement_collection_links (
    id BIGSERIAL PRIMARY KEY,
    source VARCHAR(40) NOT NULL,
    source_announcement_key VARCHAR(500) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    request_description VARCHAR(2000) NOT NULL,
    pan_id VARCHAR(100) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_lh_announcement_link_source_announcement
        UNIQUE (source, source_announcement_key)
);

CREATE INDEX IF NOT EXISTS idx_lh_announcement_link_source_request_hash
    ON lh_announcement_collection_links (source, request_hash);

-- Applied state from V20260917_01__add_lh_link_failure_reasons.sql

ALTER TABLE myhome_announcement_mapping_failures
    DROP CONSTRAINT IF EXISTS myhome_announcement_mapping_failures_reason_check,
    ADD CONSTRAINT myhome_announcement_mapping_failures_reason_check CHECK (reason IN (
        'LH_COLLECTION_REQUEST_UNSUPPORTED',
        'LH_COLLECTION_LINK_NOT_FOUND',
        'LH_COLLECTION_LINK_MISMATCH',
        'LH_SUPPLY_SOURCE_NOT_FOUND',
        'MISSING_REQUIRED_VALUE',
        'INVALID_VALUE',
        'CONFLICTING_SOURCE_VALUE',
        'PREVIOUS_ANNOUNCEMENT_NOT_FOUND',
        'CYCLIC_ANNOUNCEMENT_REVISION',
        'COMPLEX_NOT_FOUND',
        'AMBIGUOUS_COMPLEX',
        'HOUSING_TYPE_NOT_FOUND',
        'AMBIGUOUS_HOUSING_TYPE'
    ));

ALTER TABLE lh_announcement_enrichment_failures
    DROP CONSTRAINT IF EXISTS lh_announcement_enrichment_failures_reason_check,
    ADD CONSTRAINT lh_announcement_enrichment_failures_reason_check CHECK (reason IN (
        'LH_COLLECTION_REQUEST_UNSUPPORTED',
        'LH_COLLECTION_LINK_NOT_FOUND',
        'LH_COLLECTION_LINK_MISMATCH',
        'ANNOUNCEMENT_NOT_FOUND',
        'PAN_ID_NOT_FOUND',
        'LH_DETAIL_SOURCE_NOT_FOUND',
        'LH_SUPPLY_SOURCE_NOT_FOUND',
        'UNSUPPORTED_SUPPLY_TYPE',
        'INVALID_VALUE',
        'COMPLEX_NOT_FOUND',
        'AMBIGUOUS_COMPLEX',
        'HOUSING_TYPE_NOT_FOUND',
        'AMBIGUOUS_HOUSING_TYPE'
    ));

-- Applied state from V20260917_02__add_fixed_term_public_rental_types.sql

ALTER TABLE announcements
    DROP CONSTRAINT IF EXISTS announcements_supply_type_check,
    ADD CONSTRAINT announcements_supply_type_check CHECK (supply_type IN (
        'HAPPY_HOUSING',
        '행복주택',
        'NATIONAL_RENTAL',
        '국민임대',
        'PERMANENT_RENTAL',
        '영구임대',
        'PUBLIC_RENTAL_5Y',
        '5년임대',
        'PUBLIC_RENTAL_10Y',
        '10년임대',
        'PUBLIC_RENTAL_50Y',
        '50년공공임대',
        'INTEGRATED_PUBLIC_RENTAL',
        '통합공공임대',
        'REDEVELOPMENT_RENTAL',
        '재개발임대',
        'ETC',
        '기타'
    ));

-- Applied state from V20260918_01__add_lh_field_ownership.sql

ALTER TABLE announcements
    ADD COLUMN IF NOT EXISTS lh_reception_place_owned BOOLEAN;

UPDATE announcements
SET lh_reception_place_owned = CASE
    WHEN lh_pan_id IS NOT NULL AND reception_method IN ('VISIT', '현장') THEN TRUE
    ELSE FALSE
END
WHERE lh_reception_place_owned IS NULL;

ALTER TABLE announcements
    ALTER COLUMN lh_reception_place_owned SET DEFAULT FALSE,
    ALTER COLUMN lh_reception_place_owned SET NOT NULL;

ALTER TABLE supply_rows
    ADD COLUMN IF NOT EXISTS lh_total_supply_household_count_owned BOOLEAN;

UPDATE supply_rows
SET lh_total_supply_household_count_owned = CASE
    WHEN lh_source_supply_row_identifier IS NOT NULL
        AND total_supply_household_count IS NOT NULL THEN TRUE
    ELSE FALSE
END
WHERE lh_total_supply_household_count_owned IS NULL;

ALTER TABLE supply_rows
    ALTER COLUMN lh_total_supply_household_count_owned SET DEFAULT FALSE,
    ALTER COLUMN lh_total_supply_household_count_owned SET NOT NULL;

-- Applied state from V20260918_02__correct_lh_household_count_ownership.sql

ALTER TABLE supply_rows
    ADD COLUMN IF NOT EXISTS lh_total_supply_household_count_enriched BOOLEAN;

UPDATE supply_rows AS supply_row
SET lh_total_supply_household_count_owned = EXISTS (
    SELECT 1
    FROM lh_announcement_supply_source AS source
    WHERE supply_row.lh_source_supply_row_identifier =
        'LH:' || source.pan_id || ':SUPPLY:' || source.source_order
      AND (
          (
              REPLACE(BTRIM(source.total_unit_count), ',', '') ~ '^[0-9]+$'
              AND REPLACE(BTRIM(source.total_unit_count), ',', '')::NUMERIC =
                  supply_row.total_supply_household_count
          )
          OR (
              REPLACE(BTRIM(source.supplied_unit_count), ',', '') ~ '^[0-9]+$'
              AND REPLACE(BTRIM(source.supplied_unit_count), ',', '')::NUMERIC =
                  supply_row.total_supply_household_count
          )
      )
)
WHERE supply_row.lh_source_supply_row_identifier IS NOT NULL
  AND EXISTS (
      SELECT 1
      FROM lh_announcement_supply_source AS source
      WHERE supply_row.lh_source_supply_row_identifier =
          'LH:' || source.pan_id || ':SUPPLY:' || source.source_order
  );

UPDATE supply_rows AS supply_row
SET lh_total_supply_household_count_enriched = CASE
    WHEN source.id IS NULL THEN supply_row.lh_total_supply_household_count_owned
    ELSE REPLACE(BTRIM(source.total_unit_count), ',', '') ~ '^[0-9]+$'
        AND REPLACE(BTRIM(source.total_unit_count), ',', '')::NUMERIC =
            supply_row.total_supply_household_count
    END
FROM (
    SELECT row.id AS supply_row_id, source.id, source.total_unit_count
    FROM supply_rows AS row
    LEFT JOIN lh_announcement_supply_source AS source
      ON row.lh_source_supply_row_identifier =
          'LH:' || source.pan_id || ':SUPPLY:' || source.source_order
) AS source
WHERE supply_row.id = source.supply_row_id
  AND supply_row.lh_total_supply_household_count_enriched IS NULL;

UPDATE supply_rows
SET lh_total_supply_household_count_enriched = FALSE
WHERE lh_total_supply_household_count_enriched IS NULL;

ALTER TABLE supply_rows
    ALTER COLUMN lh_total_supply_household_count_enriched SET DEFAULT FALSE,
    ALTER COLUMN lh_total_supply_household_count_enriched SET NOT NULL;

-- Applied state from V20260919_01__add_data_pipeline_completed_step_reports.sql

ALTER TABLE data_pipeline_execution_completed_steps
    ADD COLUMN IF NOT EXISTS completed_report TEXT;

-- Applied state from V20260919_02__add_data_pipeline_partial_failure_reports.sql

CREATE TABLE IF NOT EXISTS data_pipeline_execution_partial_failures (
    data_pipeline_execution_id BIGINT NOT NULL,
    step_order INTEGER NOT NULL,
    partially_failed_step VARCHAR(60) NOT NULL,
    partial_failure_report TEXT NOT NULL,
    CONSTRAINT pk_data_pipeline_execution_partial_failures
        PRIMARY KEY (data_pipeline_execution_id, step_order),
    CONSTRAINT fk_data_pipeline_execution_partial_failures_execution
        FOREIGN KEY (data_pipeline_execution_id)
            REFERENCES data_pipeline_executions (id)
            ON DELETE CASCADE
);

-- Applied state from V20260919_03__add_ingest_failure_lifecycle.sql

ALTER TABLE myhome_complex_mapping_failures
    ADD COLUMN IF NOT EXISTS last_occurred_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS occurrence_count INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS recurrence_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS last_resolved_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS first_execution_id UUID,
    ADD COLUMN IF NOT EXISTS last_execution_id UUID,
    ADD COLUMN IF NOT EXISTS last_resolved_execution_id UUID;

UPDATE myhome_complex_mapping_failures
SET last_occurred_at = occurred_at
WHERE last_occurred_at IS NULL;

ALTER TABLE myhome_complex_mapping_failures
    ALTER COLUMN last_occurred_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN last_occurred_at SET NOT NULL;

ALTER TABLE myhome_announcement_mapping_failures
    ADD COLUMN IF NOT EXISTS last_occurred_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS occurrence_count INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS recurrence_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS last_resolved_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS first_execution_id UUID,
    ADD COLUMN IF NOT EXISTS last_execution_id UUID,
    ADD COLUMN IF NOT EXISTS last_resolved_execution_id UUID;

UPDATE myhome_announcement_mapping_failures
SET last_occurred_at = occurred_at
WHERE last_occurred_at IS NULL;

ALTER TABLE myhome_announcement_mapping_failures
    ALTER COLUMN last_occurred_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN last_occurred_at SET NOT NULL;

ALTER TABLE lh_announcement_enrichment_failures
    ADD COLUMN IF NOT EXISTS last_occurred_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS occurrence_count INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS recurrence_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS last_resolved_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS first_execution_id UUID,
    ADD COLUMN IF NOT EXISTS last_execution_id UUID,
    ADD COLUMN IF NOT EXISTS last_resolved_execution_id UUID;

UPDATE lh_announcement_enrichment_failures
SET last_occurred_at = occurred_at
WHERE last_occurred_at IS NULL;

ALTER TABLE lh_announcement_enrichment_failures
    ALTER COLUMN last_occurred_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN last_occurred_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_myhome_complex_mapping_failures_status_source
    ON myhome_complex_mapping_failures (status, source_key);

CREATE INDEX IF NOT EXISTS idx_myhome_complex_mapping_failures_source_reason
    ON myhome_complex_mapping_failures (source_key, reason);

CREATE INDEX IF NOT EXISTS idx_myhome_announcement_mapping_failures_status_source
    ON myhome_announcement_mapping_failures (status, source_key);

CREATE INDEX IF NOT EXISTS idx_myhome_announcement_mapping_failures_source_reason
    ON myhome_announcement_mapping_failures (source_key, reason);

CREATE INDEX IF NOT EXISTS idx_lh_announcement_enrichment_failures_status_source
    ON lh_announcement_enrichment_failures (status, source_key);

CREATE INDEX IF NOT EXISTS idx_lh_announcement_enrichment_failures_source_reason
    ON lh_announcement_enrichment_failures (source_key, reason);

-- Applied state from V20260919_04__add_external_failure_lifecycle.sql

ALTER TABLE external_data_collection_failures
    ADD COLUMN IF NOT EXISTS last_occurred_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS occurrence_count INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS recurrence_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS first_execution_id UUID,
    ADD COLUMN IF NOT EXISTS last_execution_id UUID,
    ADD COLUMN IF NOT EXISTS resolved_execution_id UUID;

UPDATE external_data_collection_failures
SET last_occurred_at = occurred_at
WHERE last_occurred_at IS NULL;

ALTER TABLE external_data_collection_failures
    ALTER COLUMN last_occurred_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN last_occurred_at SET NOT NULL;

-- Applied state from V20260919_05__create_lh_household_enrichment_failures.sql

CREATE TABLE IF NOT EXISTS lh_household_enrichment_failures (
    id BIGSERIAL PRIMARY KEY,
    source_key VARCHAR(500) NOT NULL,
    area_name VARCHAR(200),
    supply_type_name VARCHAR(200),
    complex_name VARCHAR(500),
    reason VARCHAR(40) NOT NULL,
    detail VARCHAR(1000) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    occurrence_count INTEGER NOT NULL,
    recurrence_count INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    last_resolved_at TIMESTAMP WITH TIME ZONE,
    first_execution_id UUID,
    last_execution_id UUID,
    last_resolved_execution_id UUID
);

CREATE INDEX IF NOT EXISTS idx_lh_household_enrichment_failures_status_source
    ON lh_household_enrichment_failures (status, source_key);

CREATE INDEX IF NOT EXISTS idx_lh_household_enrichment_failures_source_reason
    ON lh_household_enrichment_failures (source_key, reason);

-- Applied state from V20260921_01__add_data_pipeline_schedule_metadata.sql

ALTER TABLE data_pipeline_executions
    ADD COLUMN IF NOT EXISTS execution_trigger VARCHAR(20) DEFAULT 'MANUAL';

ALTER TABLE data_pipeline_executions
    ADD COLUMN IF NOT EXISTS scheduled_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE data_pipeline_executions
    ADD COLUMN IF NOT EXISTS upstream_execution_id UUID;

UPDATE data_pipeline_executions
SET execution_trigger = 'MANUAL'
WHERE execution_trigger IS NULL;

ALTER TABLE data_pipeline_executions
    ALTER COLUMN execution_trigger SET DEFAULT 'MANUAL';

ALTER TABLE data_pipeline_executions
    ALTER COLUMN execution_trigger SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_data_pipeline_execution_upstream
    ON data_pipeline_executions (upstream_execution_id);

CREATE UNIQUE INDEX IF NOT EXISTS ux_data_pipeline_execution_type_scheduled_at
    ON data_pipeline_executions (type, scheduled_at)
    WHERE scheduled_at IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_data_pipeline_execution_type_upstream
    ON data_pipeline_executions (type, upstream_execution_id)
    WHERE upstream_execution_id IS NOT NULL;

-- Applied state from V20260921_02__create_data_pipeline_schedule_deferrals.sql

CREATE TABLE IF NOT EXISTS data_pipeline_schedule_deferrals (
    schedule VARCHAR(30) PRIMARY KEY,
    stage VARCHAR(20) NOT NULL,
    scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reason VARCHAR(40) NOT NULL,
    detail VARCHAR(40),
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_retry_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE
);

-- Applied state from V20260921_03__allow_null_schedule_deferral_next_retry_at.sql

ALTER TABLE data_pipeline_schedule_deferrals
    ALTER COLUMN next_retry_at DROP NOT NULL;

-- Reconcile constraints skipped by pre-existing tables.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.road_address_locations'::regclass
          AND conname = 'ck_road_address_location_coordinate_pair'
    ) THEN
        ALTER TABLE public.road_address_locations
            ADD CONSTRAINT ck_road_address_location_coordinate_pair
                CHECK ((x IS NULL AND y IS NULL) OR (x IS NOT NULL AND y IS NOT NULL));
    END IF;
END $$;

DO $$
DECLARE
    child RECORD;
    existing_fk RECORD;
BEGIN
    FOR child IN
        SELECT *
        FROM (VALUES
            ('data_pipeline_execution_completed_steps', 'fk_data_pipeline_completed_step_execution'),
            ('data_pipeline_execution_skipped_steps', 'fk_data_pipeline_skipped_step_execution'),
            ('data_pipeline_execution_partial_failures', 'fk_data_pipeline_execution_partial_failures_execution')
        ) AS children(table_name, constraint_name)
    LOOP
        IF NOT EXISTS (
            SELECT 1
            FROM pg_constraint AS constraint_row
            JOIN pg_attribute AS column_row
              ON column_row.attrelid = constraint_row.conrelid
             AND column_row.attnum = constraint_row.conkey[1]
            WHERE constraint_row.conrelid = format('public.%I', child.table_name)::regclass
              AND constraint_row.confrelid = 'public.data_pipeline_executions'::regclass
              AND constraint_row.contype = 'f'
              AND array_length(constraint_row.conkey, 1) = 1
              AND column_row.attname = 'data_pipeline_execution_id'
              AND constraint_row.confdeltype = 'c'
        ) THEN
            FOR existing_fk IN
                SELECT constraint_row.conname
                FROM pg_constraint AS constraint_row
                JOIN pg_attribute AS column_row
                  ON column_row.attrelid = constraint_row.conrelid
                 AND column_row.attnum = constraint_row.conkey[1]
                WHERE constraint_row.conrelid = format('public.%I', child.table_name)::regclass
                  AND constraint_row.confrelid = 'public.data_pipeline_executions'::regclass
                  AND constraint_row.contype = 'f'
                  AND array_length(constraint_row.conkey, 1) = 1
                  AND column_row.attname = 'data_pipeline_execution_id'
            LOOP
                EXECUTE format(
                    'ALTER TABLE public.%I DROP CONSTRAINT %I',
                    child.table_name,
                    existing_fk.conname
                );
            END LOOP;

            EXECUTE format(
                'ALTER TABLE public.%I ADD CONSTRAINT %I '
                    || 'FOREIGN KEY (data_pipeline_execution_id) '
                    || 'REFERENCES public.data_pipeline_executions(id) ON DELETE CASCADE',
                child.table_name,
                child.constraint_name
            );
        END IF;
    END LOOP;
END $$;

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

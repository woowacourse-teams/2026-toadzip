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

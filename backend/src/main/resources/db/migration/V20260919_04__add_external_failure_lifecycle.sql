ALTER TABLE external_data_collection_failures
    ADD COLUMN last_occurred_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN occurrence_count INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN recurrence_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN first_execution_id UUID,
    ADD COLUMN last_execution_id UUID,
    ADD COLUMN resolved_execution_id UUID;

UPDATE external_data_collection_failures
SET last_occurred_at = occurred_at
WHERE last_occurred_at IS NULL;

ALTER TABLE external_data_collection_failures
    ALTER COLUMN last_occurred_at SET NOT NULL;

BEGIN;

ALTER TABLE data_pipeline_executions
    ADD COLUMN IF NOT EXISTS execution_trigger VARCHAR(20);

ALTER TABLE data_pipeline_executions
    ADD COLUMN IF NOT EXISTS scheduled_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE data_pipeline_executions
    ADD COLUMN IF NOT EXISTS upstream_execution_id UUID;

UPDATE data_pipeline_executions
SET execution_trigger = 'MANUAL'
WHERE execution_trigger IS NULL;

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

COMMIT;

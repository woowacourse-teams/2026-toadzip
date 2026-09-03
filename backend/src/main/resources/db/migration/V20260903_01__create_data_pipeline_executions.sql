BEGIN;

CREATE TABLE IF NOT EXISTS data_pipeline_executions (
    id BIGSERIAL PRIMARY KEY,
    execution_id UUID NOT NULL UNIQUE,
    type VARCHAR(20) NOT NULL,
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

COMMIT;

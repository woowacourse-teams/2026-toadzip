BEGIN;

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

COMMIT;

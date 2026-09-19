CREATE TABLE data_pipeline_execution_partial_failures (
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

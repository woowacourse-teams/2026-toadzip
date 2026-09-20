ALTER TABLE data_pipeline_execution_completed_steps
    ADD COLUMN IF NOT EXISTS completed_report TEXT;

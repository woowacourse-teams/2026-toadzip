BEGIN;

ALTER TABLE data_pipeline_schedule_deferrals
    ALTER COLUMN next_retry_at DROP NOT NULL;

COMMIT;

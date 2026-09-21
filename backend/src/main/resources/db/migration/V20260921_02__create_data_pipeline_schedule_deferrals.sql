BEGIN;

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

COMMIT;

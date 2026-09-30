ALTER TABLE public.data_pipeline_executions
    ADD COLUMN stop_requested boolean NOT NULL DEFAULT false,
    ADD COLUMN external_request_count bigint NOT NULL DEFAULT 0,
    ADD COLUMN last_request_description varchar(500),
    ADD COLUMN last_progress_at timestamptz;

ALTER TABLE public.data_pipeline_executions
    DROP CONSTRAINT data_pipeline_executions_status_check;
ALTER TABLE public.data_pipeline_executions
    ADD CONSTRAINT data_pipeline_executions_status_check CHECK (status IN (
        'IDLE', 'RUNNING', 'COMPLETED', 'COMPLETED_WARNINGS',
        'COMPLETED_WITH_SKIPS', 'FAILED', 'STOPPED'
    ));

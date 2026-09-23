ALTER TABLE public.data_pipeline_executions
    DROP CONSTRAINT IF EXISTS data_pipeline_executions_status_check;

ALTER TABLE public.data_pipeline_executions
    ADD CONSTRAINT data_pipeline_executions_status_check CHECK (status IN (
        'IDLE',
        'RUNNING',
        'COMPLETED',
        'COMPLETED_WARNINGS',
        'COMPLETED_WITH_SKIPS',
        'FAILED'
    ));

ALTER TABLE data_pipeline_executions
    DROP CONSTRAINT IF EXISTS data_pipeline_executions_type_check;

ALTER TABLE data_pipeline_executions
    ADD CONSTRAINT data_pipeline_executions_type_check CHECK (type IN (
        'COMPLEX_COLLECTION', 'COMPLEX_REFINEMENT',
        'ANNOUNCEMENT_COLLECTION', 'ANNOUNCEMENT_REFINEMENT',
        'COMPLEX_SYNC', 'ANNOUNCEMENT_SYNC'
    ));

ALTER TABLE data_pipeline_executions
    ADD COLUMN target_announcement_identifier VARCHAR(100);

ALTER TABLE data_pipeline_executions
    DROP CONSTRAINT data_pipeline_executions_type_check;

ALTER TABLE data_pipeline_executions
    ADD CONSTRAINT data_pipeline_executions_type_check CHECK (type IN (
        'COMPLEX_COLLECTION', 'COMPLEX_REFINEMENT',
        'ANNOUNCEMENT_COLLECTION', 'ANNOUNCEMENT_REFINEMENT',
        'COMPLEX_SYNC', 'ANNOUNCEMENT_SYNC', 'ANNOUNCEMENT_REGISTRATION'
    )),
    ADD CONSTRAINT data_pipeline_registration_target_check CHECK (
        (type = 'ANNOUNCEMENT_REGISTRATION'
            AND target_announcement_identifier IS NOT NULL
            AND LENGTH(TRIM(target_announcement_identifier)) > 0)
        OR (type <> 'ANNOUNCEMENT_REGISTRATION' AND target_announcement_identifier IS NULL)
    );

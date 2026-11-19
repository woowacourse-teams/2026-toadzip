-- 실패한 단계에서 성공한 대상이 없는 실행을 일부 실패(PARTIAL)와 구별해 남긴다. 자동 실행은 이 실행을 성공으로 세지 않는다.
ALTER TABLE data_pipeline_executions
    DROP CONSTRAINT data_pipeline_executions_failure_cause_check,
    ADD CONSTRAINT data_pipeline_executions_failure_cause_check
        CHECK (failure_cause IN ('TRANSIENT', 'PARTIAL', 'ALL_TARGETS_FAILED', 'INTERNAL'));

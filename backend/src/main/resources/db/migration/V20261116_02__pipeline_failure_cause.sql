-- 실패한 실행에 다시 실행하면 되는지(원인)와 비밀값 없는 원인 요약을 남긴다. 이전 실패는 원인을 모르므로 비워 둔다.
ALTER TABLE data_pipeline_executions
    ADD COLUMN failure_cause varchar(20),
    ADD COLUMN failure_detail varchar(500),
    ADD CONSTRAINT data_pipeline_executions_failure_cause_check
        CHECK (failure_cause IN ('TRANSIENT', 'PARTIAL', 'INTERNAL'));

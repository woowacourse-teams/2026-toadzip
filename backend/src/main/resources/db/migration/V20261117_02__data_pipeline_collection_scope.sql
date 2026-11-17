-- 자동 실행이 고른 수집 범위를 남긴다. 주 1회 전체 수집을 언제 했는지 다음 자동 실행이 읽는다.
-- 관리자 실행과 이전 실행은 범위를 고르지 않으므로 비워 두고 전체 수집으로 본다.
ALTER TABLE data_pipeline_executions
    ADD COLUMN collection_scope varchar(20),
    ADD CONSTRAINT data_pipeline_executions_collection_scope_check
        CHECK (collection_scope IN ('FULL', 'INCREMENTAL'));

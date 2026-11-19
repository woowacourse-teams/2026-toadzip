-- 결과를 만든 워커 버전(워커 소스와 설정의 지문). 같은 PDF·입력·버전으로 성공한 작업이 있으면 워커는 모델을 다시 부르지 않고
-- 그 결과를 쓴다. 이 열이 생기기 전의 작업은 버전이 없어 다시 쓰지 않는다.
ALTER TABLE ai_jobs ADD COLUMN worker_version VARCHAR(64);

CREATE INDEX idx_ai_jobs_reusable
    ON ai_jobs (kind, pdf_sha256, completed_at DESC, id DESC) WHERE status = 'SUCCEEDED' AND worker_version IS NOT NULL;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'toadzip_ai_worker') THEN
        GRANT UPDATE (worker_version) ON ai_jobs TO toadzip_ai_worker;
    END IF;
END
$$;

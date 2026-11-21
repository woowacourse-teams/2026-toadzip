CREATE TABLE pdf_publication_decisions (
    job_id bigint PRIMARY KEY REFERENCES ai_jobs(id) ON DELETE CASCADE,
    outcome varchar(16) NOT NULL CHECK (outcome IN ('REVIEW','WOULD_PUBLISH','PUBLISHED','WOULD_CONFIRM','CONFIRMED')),
    reason text NOT NULL,
    eligibility_status varchar(16),
    published_version bigint,
    decided_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_ai_jobs_publication_pending ON ai_jobs(id)
    WHERE request_source = 'BATCH' AND status = 'SUCCEEDED' AND input -> 'publication' IS NOT NULL;

\set ON_ERROR_STOP on

-- Usage:
-- psql -v snapshot_id=123 -f retry-failed-lh-notice-snapshot.sql

BEGIN;

UPDATE external_api_data
SET lh_notice_processing_status = 'PENDING',
    lh_notice_processed_at = NULL
WHERE id = :'snapshot_id'::bigint
  AND external_api = 'MYHOME_NOTICE'
  AND lh_notice_processing_status = 'FAILED'
RETURNING id, request_description, lh_notice_processing_status;

SELECT :ROW_COUNT = 1 AS retry_succeeded \gset

\if :retry_succeeded
\else
    ROLLBACK;
    DO $$
    BEGIN
        RAISE EXCEPTION 'FAILED 상태의 MYHOME_NOTICE snapshot 한 건을 변경하지 못했습니다.';
    END
    $$;
\endif

COMMIT;

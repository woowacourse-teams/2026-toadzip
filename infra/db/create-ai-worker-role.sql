-- Run once on each primary PostgreSQL instance (dev, prod, local), connected to the toadzip database as its administrator.
-- Set a distinct password interactively with: \password toadzip_ai_worker
-- The worker only reads ai_jobs and updates its state/result columns. It never inserts, deletes or touches other tables.
CREATE ROLE toadzip_ai_worker LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION CONNECTION LIMIT 5;
GRANT CONNECT ON DATABASE toadzip TO toadzip_ai_worker;
GRANT USAGE ON SCHEMA public TO toadzip_ai_worker;
ALTER ROLE toadzip_ai_worker SET statement_timeout = '30s';
ALTER ROLE toadzip_ai_worker SET lock_timeout = '5s';

-- The ai_jobs migration grants these when the role already exists. Repeat them for a database migrated before this script.
DO $$
BEGIN
    IF to_regclass('public.ai_jobs') IS NOT NULL THEN
        GRANT SELECT ON ai_jobs TO toadzip_ai_worker;
        GRANT UPDATE (status, attempts, next_attempt_at, lease_token, lease_until, pdf, result, error_code,
                error_message, started_at, completed_at) ON ai_jobs TO toadzip_ai_worker;
    END IF;
END
$$;

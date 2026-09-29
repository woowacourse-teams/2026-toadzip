-- Run only after the application has applied V20260922_02 successfully.
-- Usage: psql ... -X -v ON_ERROR_STOP=1 -v expected_db=toadzip -f scripts/flyway-login-constraint-finalize.sql
BEGIN;
SELECT set_config('toadzip.expected_db', :'expected_db', true);

DO $$
DECLARE
    current_definition text;
    parked_definition text;
BEGIN
    IF current_database() <> current_setting('toadzip.expected_db') THEN
        RAISE EXCEPTION 'Wrong database: expected %, connected to %',
                current_setting('toadzip.expected_db'), current_database();
    END IF;
    IF to_regclass('public.flyway_schema_history') IS NULL THEN
        RAISE EXCEPTION 'Flyway history is missing';
    END IF;
    IF NOT EXISTS (
            SELECT 1 FROM public.flyway_schema_history
            WHERE version = '20260922.02' AND success = true) THEN
        RAISE EXCEPTION 'V20260922_02 has not succeeded';
    END IF;
    SELECT pg_get_constraintdef(oid) INTO current_definition
    FROM pg_constraint
    WHERE conrelid = 'public.users'::regclass AND conname = 'uk_users_login_identifier';
    SELECT pg_get_constraintdef(oid) INTO parked_definition
    FROM pg_constraint
    WHERE conrelid = 'public.users'::regclass AND conname = 'uk_users_login_identifier_pre_flyway';
    IF current_definition <> 'UNIQUE (login_identifier)' OR current_definition IS NULL THEN
        RAISE EXCEPTION 'Flyway unique constraint is missing or unexpected';
    END IF;
    IF parked_definition IS NULL THEN
        RAISE NOTICE 'Legacy constraint was already finalized; no change';
        RETURN;
    END IF;
    IF parked_definition <> current_definition THEN
        RAISE EXCEPTION 'Legacy and Flyway constraints differ';
    END IF;
    ALTER TABLE public.users DROP CONSTRAINT uk_users_login_identifier_pre_flyway;
    RAISE NOTICE 'Redundant legacy constraint removed after Flyway verification';
END $$;
COMMIT;

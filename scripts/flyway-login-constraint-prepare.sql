-- One-time preparation for a known legacy database without Flyway history.
-- Usage: psql ... -X -v ON_ERROR_STOP=1 -v expected_db=toadzip -f scripts/flyway-login-constraint-prepare.sql
-- Stop application writes and back up the target database before running this script.
BEGIN;
SELECT set_config('toadzip.expected_db', :'expected_db', true);

DO $$
DECLARE
    existing_definition text;
    parked_definition text;
BEGIN
    IF current_database() <> current_setting('toadzip.expected_db') THEN
        RAISE EXCEPTION 'Wrong database: expected %, connected to %',
                current_setting('toadzip.expected_db'), current_database();
    END IF;
    IF to_regclass('public.flyway_schema_history') IS NOT NULL THEN
        RAISE EXCEPTION 'Flyway history already exists; inspect it before running preparation';
    END IF;
    IF to_regclass('public.users') IS NULL
            OR to_regclass('public.announcements') IS NULL
            OR to_regclass('public.myhome_announcement_source') IS NULL THEN
        RAISE EXCEPTION 'Expected legacy schema anchors are missing';
    END IF;

    SELECT pg_get_constraintdef(oid) INTO existing_definition
    FROM pg_constraint
    WHERE conrelid = 'public.users'::regclass AND conname = 'uk_users_login_identifier';
    SELECT pg_get_constraintdef(oid) INTO parked_definition
    FROM pg_constraint
    WHERE conrelid = 'public.users'::regclass AND conname = 'uk_users_login_identifier_pre_flyway';

    IF parked_definition IS NOT NULL THEN
        IF parked_definition <> 'UNIQUE (login_identifier)' OR existing_definition IS NOT NULL THEN
            RAISE EXCEPTION 'Unexpected pre-Flyway constraint state';
        END IF;
        RAISE NOTICE 'Constraint was already prepared; no change';
        RETURN;
    END IF;
    IF existing_definition IS NULL THEN
        RAISE EXCEPTION 'Expected uk_users_login_identifier constraint is missing';
    END IF;
    IF existing_definition <> 'UNIQUE (login_identifier)' THEN
        RAISE EXCEPTION 'Unexpected constraint definition: %', existing_definition;
    END IF;
    IF EXISTS (SELECT 1 FROM public.users GROUP BY login_identifier HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'Duplicate login identifiers must be resolved first';
    END IF;

    ALTER TABLE public.users
        RENAME CONSTRAINT uk_users_login_identifier TO uk_users_login_identifier_pre_flyway;
    RAISE NOTICE 'Legacy unique constraint renamed; uniqueness remains enforced';
END $$;
COMMIT;

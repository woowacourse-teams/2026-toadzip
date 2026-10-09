-- Read-only preflight/post-deployment gate for the primary DB. Never applies DDL or repairs history.
BEGIN READ ONLY;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
SELECT set_config('toadzip.expected_db', :'expected_db', true);
SELECT set_config('toadzip.privacy_phase', :'phase', true);

DO $$
DECLARE
    phase text := current_setting('toadzip.privacy_phase');
    expected record;
BEGIN
    IF current_database() <> current_setting('toadzip.expected_db') THEN
        RAISE EXCEPTION 'Wrong database: expected %, connected to %',
            current_setting('toadzip.expected_db'), current_database();
    END IF;
    IF phase NOT IN ('before', 'after') THEN
        RAISE EXCEPTION 'Unknown privacy schema phase: %', phase;
    END IF;
    IF to_regclass('public.flyway_schema_history') IS NULL THEN
        IF phase = 'before' AND NOT EXISTS (
                SELECT 1 FROM pg_class tables JOIN pg_namespace schemas ON schemas.oid = tables.relnamespace
                WHERE schemas.nspname = 'public' AND tables.relkind IN ('r', 'p', 'f')) THEN
            RAISE NOTICE 'Empty primary DB: Flyway will create the initial schema at application startup';
            RETURN;
        END IF;
        RAISE EXCEPTION 'Flyway history is missing; inspect flyway-adoption.md before deployment';
    END IF;
    IF EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE NOT success) THEN
        RAISE EXCEPTION 'Flyway history contains a failed migration; do not repair it automatically';
    END IF;

    FOR expected IN SELECT * FROM (VALUES
            ('analytics_consents', '20261009.01'),
            ('analytics_consent_events', '20261009.01'),
            ('user_deletion_markers', '20261009.03')) AS entries(table_name, version) LOOP
        IF (to_regclass('public.' || expected.table_name) IS NOT NULL) <> EXISTS (
                SELECT 1 FROM public.flyway_schema_history WHERE version = expected.version AND success) THEN
            RAISE EXCEPTION 'Table % and Flyway version % disagree', expected.table_name, expected.version;
        END IF;
    END LOOP;
    FOR expected IN SELECT * FROM (VALUES
            ('users', 'notification_settings_revision', '20261009.02'),
            ('users', 'registration_policy_version', '20261009.03'),
            ('notification_subscriptions', 'notice_version', '20261009.02'),
            ('notification_subscriptions', 'requested_at', '20261009.02'),
            ('notification_subscriptions', 'purge_after', '20261009.02'),
            ('notification_interest_events', 'user_id', '20261009.02'),
            ('notification_interest_events', 'notice_version', '20261009.02'),
            ('notification_interest_events', 'settings_revision', '20261009.02'))
            AS entries(table_name, column_name, version) LOOP
        IF EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = expected.table_name
                     AND column_name = expected.column_name) <> EXISTS (
                SELECT 1 FROM public.flyway_schema_history WHERE version = expected.version AND success) THEN
            RAISE EXCEPTION 'Column %.% and Flyway version % disagree',
                expected.table_name, expected.column_name, expected.version;
        END IF;
    END LOOP;
    IF phase = 'before' THEN
        RAISE NOTICE 'Privacy preflight passed; Flyway will validate checksums and apply pending migrations';
        RETURN;
    END IF;

    FOR expected IN SELECT * FROM (VALUES ('20261009.01'), ('20261009.02'),
            ('20261009.03'), ('20261009.04')) AS entries(version) LOOP
        IF NOT EXISTS (SELECT 1 FROM public.flyway_schema_history
                       WHERE version = expected.version AND success) THEN
            RAISE EXCEPTION 'Privacy migration % is not recorded as successful', expected.version;
        END IF;
    END LOOP;
    FOR expected IN SELECT * FROM (VALUES
            ('users', 'email', 'character varying', 'YES'),
            ('users', 'notification_settings_revision', 'bigint', 'NO'),
            ('users', 'registration_policy_version', 'character varying', 'YES'),
            ('analytics_consents', 'id', 'uuid', 'NO'),
            ('analytics_consents', 'user_id', 'bigint', 'YES'),
            ('analytics_consents', 'guest_token_hash', 'character varying', 'YES'),
            ('analytics_consents', 'revision', 'bigint', 'NO'),
            ('analytics_consent_events', 'consent_id', 'uuid', 'NO'),
            ('analytics_consent_events', 'command_id', 'uuid', 'NO'),
            ('analytics_consent_events', 'purge_after', 'timestamp with time zone', 'YES'),
            ('notification_subscriptions', 'notice_version', 'character varying', 'YES'),
            ('notification_subscriptions', 'requested_at', 'timestamp with time zone', 'YES'),
            ('notification_subscriptions', 'purge_after', 'timestamp with time zone', 'YES'),
            ('notification_interest_events', 'session_id', 'uuid', 'YES'),
            ('notification_interest_events', 'user_id', 'bigint', 'YES'),
            ('notification_interest_events', 'notice_version', 'character varying', 'YES'),
            ('notification_interest_events', 'settings_revision', 'bigint', 'YES'),
            ('user_deletion_markers', 'login_identifier_hash', 'character varying', 'NO'),
            ('user_deletion_markers', 'deleted_at', 'timestamp with time zone', 'NO'),
            ('user_deletion_markers', 'expires_at', 'timestamp with time zone', 'NO'))
            AS entries(table_name, column_name, data_type, is_nullable) LOOP
        IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema = 'public' AND table_name = expected.table_name
                         AND column_name = expected.column_name AND data_type = expected.data_type
                         AND is_nullable = expected.is_nullable) THEN
            RAISE EXCEPTION 'Missing or incompatible column %.%', expected.table_name, expected.column_name;
        END IF;
    END LOOP;
    FOR expected IN SELECT * FROM (VALUES
            ('analytics_consents', 'analytics_consents_pkey', 'p'),
            ('analytics_consents', 'analytics_consents_user_id_key', 'u'),
            ('analytics_consents', 'analytics_consents_guest_token_hash_key', 'u'),
            ('analytics_consent_events', 'analytics_consent_events_consent_id_command_id_key', 'u'),
            ('analytics_consent_events', 'analytics_consent_events_consent_id_revision_key', 'u'),
            ('notification_interest_events', 'ck_notification_member_receipt', 'c'),
            ('user_deletion_markers', 'user_deletion_markers_pkey', 'p'),
            ('user_deletion_markers', 'ck_user_deletion_marker_expiry', 'c'))
            AS entries(table_name, constraint_name, constraint_type) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_constraint
                       WHERE conrelid = to_regclass('public.' || expected.table_name)
                         AND conname = expected.constraint_name AND contype::text = expected.constraint_type
                         AND convalidated) THEN
            RAISE EXCEPTION 'Required constraint % is missing or invalid', expected.constraint_name;
        END IF;
    END LOOP;
    FOR expected IN SELECT * FROM (VALUES
            ('analytics_consents', 'analytics_consents_user_id_fkey', 'users'),
            ('analytics_consent_events', 'analytics_consent_events_consent_id_fkey', 'analytics_consents'),
            ('notification_interest_events', 'notification_interest_events_user_id_fkey', 'users'))
            AS entries(table_name, constraint_name, parent_name) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_constraint
                       WHERE conrelid = to_regclass('public.' || expected.table_name)
                         AND conname = expected.constraint_name AND contype = 'f' AND confdeltype = 'c'
                         AND confrelid = to_regclass('public.' || expected.parent_name) AND convalidated) THEN
            RAISE EXCEPTION 'Cascading foreign key % is missing or invalid', expected.constraint_name;
        END IF;
    END LOOP;
    FOR expected IN SELECT * FROM (VALUES
            ('analytics_consents_guest_expiry_idx', 'analytics_consents'),
            ('analytics_consent_events_purge_idx', 'analytics_consent_events'),
            ('idx_notification_subscriptions_purge_after', 'notification_subscriptions'),
            ('idx_notification_interest_events_retention', 'notification_interest_events'),
            ('idx_notification_interest_events_user', 'notification_interest_events'),
            ('idx_user_deletion_markers_expiry', 'user_deletion_markers')) AS entries(index_name, table_name) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_index
                       WHERE indexrelid = to_regclass('public.' || expected.index_name)
                         AND indrelid = to_regclass('public.' || expected.table_name)
                         AND indisvalid AND indisready) THEN
            RAISE EXCEPTION 'Required index % is missing or invalid', expected.index_name;
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM public.notification_subscriptions WHERE purge_after IS NULL) THEN
        RAISE EXCEPTION 'Notification retention backfill is incomplete; stop old application writers';
    END IF;
    RAISE NOTICE 'Privacy schema and retention backfill verified in %', current_database();
END $$;

SELECT current_database() AS database_name, inet_server_addr() AS server_address,
       inet_server_port() AS server_port, CURRENT_TIMESTAMP AS checked_at;
COMMIT;

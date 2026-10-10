-- Read-only primary DB gate. Never changes application tables or migration history.
BEGIN READ ONLY;
SELECT set_config('toadzip.expected_db', :'expected_db', true);
SELECT set_config('toadzip.privacy_phase', :'phase', true);
DO $$
BEGIN
    IF current_database() <> current_setting('toadzip.expected_db') THEN
        RAISE EXCEPTION 'Wrong database: expected %, connected to %',
            current_setting('toadzip.expected_db'), current_database();
    END IF;
    IF current_setting('toadzip.privacy_phase') NOT IN ('before', 'after') THEN
        RAISE EXCEPTION 'Unknown privacy schema phase';
    END IF;
END $$;
\ir ../backend/src/main/resources/privacy/schema-preflight.sql
DO $$
DECLARE
    expected record;
BEGIN
    IF current_setting('toadzip.privacy_phase') = 'before' THEN
        RAISE NOTICE 'Privacy preflight passed; application and dedicated privacy Flyway still validate checksums';
        RETURN;
    END IF;
    IF to_regclass('public.privacy_flyway_schema_history') IS NULL THEN
        RAISE EXCEPTION 'Dedicated privacy Flyway history is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.privacy_flyway_schema_history
                   WHERE version = '1' AND type = 'SQL' AND success) THEN
        RAISE EXCEPTION 'Dedicated privacy V1 migration is not recorded as successful';
    END IF;
    FOR expected IN SELECT * FROM (VALUES ('privacy_analytics_consents'), ('privacy_analytics_consent_events'),
            ('privacy_registration_notices'), ('privacy_notification_states'), ('privacy_notification_notices'),
            ('privacy_notification_receipts'), ('privacy_notification_events')) AS entries(table_name) LOOP
        IF to_regclass('public.' || expected.table_name) IS NULL THEN
            RAISE EXCEPTION 'Privacy add-on table % is missing', expected.table_name;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_constraint
                       WHERE conrelid = to_regclass('public.' || expected.table_name) AND contype = 'p') THEN
            RAISE EXCEPTION 'Privacy add-on primary key is missing on %', expected.table_name;
        END IF;
    END LOOP;
    FOR expected IN SELECT * FROM (VALUES
            ('privacy_analytics_consents', 'id', 'uuid', 'NO'),
            ('privacy_analytics_consents', 'user_id', 'bigint', 'YES'),
            ('privacy_analytics_consents', 'guest_token_hash', 'character varying', 'YES'),
            ('privacy_analytics_consents', 'revision', 'bigint', 'NO'),
            ('privacy_analytics_consent_events', 'consent_id', 'uuid', 'NO'),
            ('privacy_analytics_consent_events', 'command_id', 'uuid', 'NO'),
            ('privacy_analytics_consent_events', 'purge_after', 'timestamp with time zone', 'YES'),
            ('privacy_registration_notices', 'policy_version', 'character varying', 'NO'),
            ('privacy_registration_notices', 'recorded_at', 'timestamp with time zone', 'NO'),
            ('privacy_registration_notices', 'purge_after', 'timestamp with time zone', 'YES'),
            ('privacy_notification_states', 'revision', 'bigint', 'NO'),
            ('privacy_notification_states', 'updated_at', 'timestamp with time zone', 'NO'),
            ('privacy_notification_notices', 'notice_version', 'character varying', 'NO'),
            ('privacy_notification_notices', 'requested_at', 'timestamp with time zone', 'NO'),
            ('privacy_notification_notices', 'expires_at', 'timestamp with time zone', 'NO'),
            ('privacy_notification_notices', 'purge_after', 'timestamp with time zone', 'NO'),
            ('privacy_notification_receipts', 'settings_revision', 'bigint', 'NO'),
            ('privacy_notification_receipts', 'notice_version', 'character varying', 'NO'),
            ('privacy_notification_receipts', 'purge_after', 'timestamp with time zone', 'NO'),
            ('privacy_notification_events', 'session_id', 'uuid', 'NO'),
            ('privacy_notification_events', 'request_fingerprint', 'character varying', 'NO'),
            ('privacy_notification_events', 'purge_after', 'timestamp with time zone', 'NO'))
            AS entries(table_name, column_name, data_type, is_nullable) LOOP
        IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema = 'public' AND table_name = expected.table_name
                         AND column_name = expected.column_name AND data_type = expected.data_type
                         AND is_nullable = expected.is_nullable) THEN
            RAISE EXCEPTION 'Missing or incompatible column %.%', expected.table_name, expected.column_name;
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_constraint constraints
               JOIN pg_class child ON child.oid = constraints.conrelid
               JOIN pg_class parent ON parent.oid = constraints.confrelid
               JOIN pg_namespace child_schema ON child_schema.oid = child.relnamespace
               JOIN pg_namespace parent_schema ON parent_schema.oid = parent.relnamespace
               WHERE constraints.contype = 'f' AND child_schema.nspname = 'public'
                 AND parent_schema.nspname = 'public'
                 AND ((child.relname LIKE 'privacy\_%' ESCAPE '\')
                     <> (parent.relname LIKE 'privacy\_%' ESCAPE '\'))) THEN
        RAISE EXCEPTION 'A foreign key couples privacy add-on tables to existing application tables';
    END IF;
    FOR expected IN SELECT * FROM (VALUES
            ('privacy_analytics_consents_guest_expiry_idx', 'privacy_analytics_consents'),
            ('privacy_analytics_consent_events_purge_idx', 'privacy_analytics_consent_events'),
            ('privacy_registration_notices_purge_idx', 'privacy_registration_notices'),
            ('privacy_notification_notices_purge_idx', 'privacy_notification_notices'),
            ('privacy_notification_receipts_purge_idx', 'privacy_notification_receipts'),
            ('privacy_notification_receipts_user_idx', 'privacy_notification_receipts'),
            ('privacy_notification_events_purge_idx', 'privacy_notification_events')) AS entries(index_name, table_name) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_index
                       WHERE indexrelid = to_regclass('public.' || expected.index_name)
                         AND indrelid = to_regclass('public.' || expected.table_name)
                         AND indisvalid AND indisready) THEN
            RAISE EXCEPTION 'Required privacy index % is missing or invalid', expected.index_name;
        END IF;
    END LOOP;
    RAISE NOTICE 'Dedicated privacy schema verified in %', current_database();
END $$;
SELECT current_database() AS database_name, inet_server_addr() AS server_address,
       inet_server_port() AS server_port, CURRENT_TIMESTAMP AS checked_at;
COMMIT;

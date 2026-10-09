-- Read-only guard shared by application startup and the deployment gate.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
DO $$
DECLARE
    expected record;
BEGIN
    IF to_regclass('public.flyway_schema_history') IS NULL THEN
        IF EXISTS (SELECT 1 FROM pg_class tables JOIN pg_namespace schemas ON schemas.oid = tables.relnamespace
                   WHERE schemas.nspname = 'public' AND tables.relkind IN ('r', 'p', 'f')) THEN
            RAISE EXCEPTION 'Application Flyway history is missing in a nonempty database; stop and inspect adoption';
        END IF;
        RETURN;
    END IF;
    IF EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE NOT success) THEN
        RAISE EXCEPTION 'Application Flyway history contains a failure; never repair automatically';
    END IF;
    IF EXISTS (SELECT 1 FROM public.flyway_schema_history
               WHERE version IN ('20261009.01', '20261009.02', '20261009.03', '20261009.04')) THEN
        RAISE EXCEPTION 'Legacy privacy migrations were applied; stop for separately approved database recovery';
    END IF;
    FOR expected IN SELECT * FROM (VALUES ('analytics_consents'), ('analytics_consent_events'),
            ('user_deletion_markers')) AS entries(table_name) LOOP
        IF to_regclass('public.' || expected.table_name) IS NOT NULL THEN
            RAISE EXCEPTION 'Legacy privacy table % exists; recovery must be approved separately', expected.table_name;
        END IF;
    END LOOP;
    FOR expected IN SELECT * FROM (VALUES
            ('users', 'notification_settings_revision'), ('users', 'registration_policy_version'),
            ('notification_subscriptions', 'notice_version'), ('notification_subscriptions', 'requested_at'),
            ('notification_subscriptions', 'purge_after'), ('notification_interest_events', 'user_id'),
            ('notification_interest_events', 'notice_version'), ('notification_interest_events', 'settings_revision'))
            AS entries(table_name, column_name) LOOP
        IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public'
                   AND table_name = expected.table_name AND column_name = expected.column_name) THEN
            RAISE EXCEPTION 'Legacy privacy column %.% exists; stop for recovery',
                expected.table_name, expected.column_name;
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public'
               AND table_name = 'notification_interest_events' AND column_name = 'session_id'
               AND is_nullable <> 'NO') THEN
        RAISE EXCEPTION 'Legacy notification session constraint was changed; stop for recovery';
    END IF;
    FOR expected IN SELECT * FROM (VALUES ('idx_notification_subscriptions_purge_after'),
            ('idx_notification_interest_events_retention'), ('idx_notification_interest_events_user'))
            AS entries(index_name) LOOP
        IF to_regclass('public.' || expected.index_name) IS NOT NULL THEN
            RAISE EXCEPTION 'Legacy privacy index % exists; stop for recovery', expected.index_name;
        END IF;
    END LOOP;
    IF to_regclass('public.privacy_flyway_schema_history') IS NULL THEN
        IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public'
                   AND table_name LIKE 'privacy\_%' ESCAPE '\') THEN
            RAISE EXCEPTION 'Privacy add-on tables exist without their dedicated Flyway history';
        END IF;
        RETURN;
    END IF;
    IF EXISTS (SELECT 1 FROM public.privacy_flyway_schema_history WHERE NOT success) THEN
        RAISE EXCEPTION 'Privacy Flyway history contains a failure; never repair automatically';
    END IF;
END $$;

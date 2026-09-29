-- Read-only deployment gate. Run with psql -X -v ON_ERROR_STOP=1 -v expected_db=... -f.
BEGIN READ ONLY;
SELECT set_config('toadzip.expected_db', :'expected_db', true);

DO $$
DECLARE
    expected_version text;
    expected_table text;
BEGIN
    IF current_database() <> current_setting('toadzip.expected_db') THEN
        RAISE EXCEPTION 'Wrong database: expected %, connected to %',
                current_setting('toadzip.expected_db'), current_database();
    END IF;
    IF to_regclass('public.flyway_schema_history') IS NULL THEN
        RAISE EXCEPTION 'Flyway history is missing';
    END IF;
    IF EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE success = false) THEN
        RAISE EXCEPTION 'Flyway history contains a failed migration';
    END IF;
    FOREACH expected_version IN ARRAY ARRAY[
            '20260922.02', '20260927.01', '20260928.01', '20260929.01',
            '20260929.02', '20260929.03', '20260929.04', '20260929.05',
            '20260930.01'] LOOP
        IF NOT EXISTS (
                SELECT 1 FROM public.flyway_schema_history
                WHERE version = expected_version AND success = true) THEN
            RAISE EXCEPTION 'Migration % is not recorded as successful', expected_version;
        END IF;
    END LOOP;
    FOREACH expected_table IN ARRAY ARRAY[
            'notification_interest_events', 'notification_email_preferences',
            'notification_subscriptions', 'notification_guest_email_preferences',
            'notification_guest_subscriptions', 'notification_guest_cancellation_requests'] LOOP
        IF to_regclass('public.' || expected_table) IS NULL THEN
            RAISE EXCEPTION 'Table % is missing', expected_table;
        END IF;
    END LOOP;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'email')
            OR NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'notification_subscriptions'
                     AND column_name = 'expires_at')
            OR NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'notification_guest_subscriptions'
                     AND column_name = 'expires_at')
            OR NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'notification_guest_cancellation_requests'
                     AND column_name = 'code_sent_at')
            OR NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'notification_guest_cancellation_requests'
                     AND column_name = 'code_sent_by') THEN
        RAISE EXCEPTION 'Notification email, expiry, or send tracking column is missing';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'notification_interest_events'
                 AND column_name = 'email') THEN
        RAISE EXCEPTION 'Event email column still exists';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'public.notification_interest_events'::regclass
                     AND conname = 'notification_interest_events_event_type_check'
                     AND pg_get_constraintdef(oid) LIKE '%CANCELLED%') THEN
        RAISE EXCEPTION 'Cancellation event constraint is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'public.notification_subscriptions'::regclass
                     AND conname = 'uk_notification_subscription_target')
            OR NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'public.notification_guest_subscriptions'::regclass
                     AND conname = 'uk_notification_guest_subscription_target') THEN
        RAISE EXCEPTION 'Subscription uniqueness constraint is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'public.users'::regclass
                     AND conname = 'uk_users_login_identifier'
                     AND pg_get_constraintdef(oid) = 'UNIQUE (login_identifier)')
            OR EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'public.users'::regclass
                     AND conname = 'uk_users_login_identifier_pre_flyway') THEN
        RAISE EXCEPTION 'Login identifier constraint correction is incomplete';
    END IF;
    RAISE NOTICE 'Notification schema verified in %', current_database();
END $$;

SELECT current_database() AS database_name,
       inet_server_addr() AS server_address,
       inet_server_port() AS server_port,
       CURRENT_TIMESTAMP AS checked_at;
COMMIT;

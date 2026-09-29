-- Read-only check. Run with psql -X -v ON_ERROR_STOP=1 -f on each environment's primary DB.
SELECT current_database() AS database_name,
       current_schema() AS schema_name,
       inet_server_addr() AS server_address,
       inet_server_port() AS server_port,
       current_user AS database_user;

SELECT to_regclass('public.flyway_schema_history') IS NOT NULL AS history_present;

SELECT CASE WHEN to_regclass('public.flyway_schema_history') IS NULL
            THEN 'false' ELSE 'true' END AS history_present \gset

\if :history_present
SELECT installed_rank, version, type, script, success
FROM public.flyway_schema_history
ORDER BY installed_rank;

WITH expected(version) AS (
    VALUES ('20260922.02'), ('20260927.01'), ('20260928.01'), ('20260929.01'),
           ('20260929.02'), ('20260929.03'), ('20260929.04'), ('20260929.05')
)
SELECT expected.version,
       COALESCE(bool_or(history.success), false) AS applied_successfully
FROM expected
LEFT JOIN public.flyway_schema_history history ON history.version = expected.version
GROUP BY expected.version
ORDER BY expected.version;
\else
SELECT 'No Flyway history: back up and inspect an existing DB before automatic baseline.' AS migration_status;
\endif

SELECT to_regclass('public.notification_interest_events') IS NOT NULL AS event_table_exists,
       to_regclass('public.notification_email_preferences') IS NOT NULL AS email_preference_table_exists,
       to_regclass('public.notification_subscriptions') IS NOT NULL AS subscription_table_exists,
       to_regclass('public.notification_guest_email_preferences') IS NOT NULL AS guest_email_table_exists,
       to_regclass('public.notification_guest_subscriptions') IS NOT NULL AS guest_subscription_table_exists,
       to_regclass('public.notification_guest_cancellation_requests') IS NOT NULL AS guest_cancellation_table_exists,
       EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'email') AS user_email_column_exists,
       EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'notification_interest_events'
                 AND column_name = 'email') AS event_email_column_remains,
       EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'notification_subscriptions'
                 AND column_name = 'expires_at') AS member_expiry_column_exists,
       EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'notification_guest_subscriptions'
                 AND column_name = 'expires_at') AS guest_expiry_column_exists,
       EXISTS (SELECT 1 FROM pg_constraint
               WHERE conname = 'notification_interest_events_event_type_check'
                 AND pg_get_constraintdef(oid) LIKE '%CANCELLED%') AS cancellation_allowed,
       EXISTS (SELECT 1 FROM pg_constraint
               WHERE conrelid = to_regclass('public.users')
                 AND conname = 'uk_users_login_identifier'
                 AND pg_get_constraintdef(oid) = 'UNIQUE (login_identifier)') AS login_identifier_constraint_complete,
       EXISTS (SELECT 1 FROM pg_constraint
               WHERE conrelid = to_regclass('public.users')
                 AND conname = 'uk_users_login_identifier_pre_flyway') AS temporary_login_constraint_remains;

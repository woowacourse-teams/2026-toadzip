-- Read-only inspection of retained legacy member data.
SELECT current_database() AS database_name, CURRENT_TIMESTAMP AS checked_at;
SELECT (SELECT count(*) FROM notification_interest_events
        WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '90 days') AS old_legacy_events,
       (SELECT count(*) FROM notification_email_preferences preferences
        WHERE NOT EXISTS (
            SELECT 1 FROM notification_subscriptions subscriptions
            WHERE subscriptions.user_id = preferences.user_id AND subscriptions.active = true
              AND subscriptions.expires_at > CURRENT_TIMESTAMP)) AS stale_member_emails;

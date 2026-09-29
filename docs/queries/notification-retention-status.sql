-- Read-only operational check; identify the database before reading the counts.
SELECT current_database() AS database_name, CURRENT_TIMESTAMP AS checked_at;

SELECT (SELECT count(*) FROM notification_interest_events
        WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '90 days') AS overdue_events,
       (SELECT count(*) FROM notification_guest_cancellation_requests
        WHERE requested_at < CURRENT_TIMESTAMP - INTERVAL '30 days') AS overdue_cancellation_requests,
       (SELECT count(*) FROM notification_email_preferences preferences
        WHERE NOT EXISTS (
            SELECT 1 FROM notification_subscriptions subscriptions
            WHERE subscriptions.user_id = preferences.user_id AND subscriptions.active = true
              AND subscriptions.expires_at > CURRENT_TIMESTAMP)) AS stale_member_emails,
       (SELECT count(*) FROM notification_guest_email_preferences preferences
        WHERE NOT EXISTS (
            SELECT 1 FROM notification_guest_subscriptions subscriptions
            WHERE subscriptions.client_id = preferences.client_id AND subscriptions.active = true
              AND subscriptions.expires_at > CURRENT_TIMESTAMP)) AS stale_guest_emails;

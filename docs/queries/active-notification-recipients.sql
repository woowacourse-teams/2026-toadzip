-- Read-only recipient list for an authorized operator. Contains personal data.
-- This query does not send email or decide which new announcement matches a target.
-- Usage: psql -X -v ON_ERROR_STOP=1 -v target_type=REGION -v target_id=11 -f ...
BEGIN READ ONLY;
SELECT DISTINCT email, target_type, target_id
FROM (
    SELECT preferences.email, subscriptions.target_type, subscriptions.target_id
    FROM notification_subscriptions subscriptions
    JOIN notification_email_preferences preferences ON preferences.user_id = subscriptions.user_id
    WHERE subscriptions.active = true

    UNION ALL

    SELECT preferences.email, subscriptions.target_type, subscriptions.target_id
    FROM notification_guest_subscriptions subscriptions
    JOIN notification_guest_email_preferences preferences ON preferences.client_id = subscriptions.client_id
    WHERE subscriptions.active = true
) recipients
WHERE target_type = :'target_type' AND target_id = :'target_id'
ORDER BY target_type, target_id, email;
COMMIT;

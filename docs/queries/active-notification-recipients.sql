-- Legacy member email preferences only; not the complete member applicant list.
-- This read-only query does not authorize or send notification email.
BEGIN READ ONLY;
SELECT DISTINCT preferences.email, subscriptions.target_type, subscriptions.target_id
FROM notification_subscriptions subscriptions
JOIN notification_email_preferences preferences ON preferences.user_id = subscriptions.user_id
WHERE subscriptions.active = true AND subscriptions.expires_at > CURRENT_TIMESTAMP
  AND subscriptions.target_type = :'target_type' AND subscriptions.target_id = :'target_id'
ORDER BY subscriptions.target_type, subscriptions.target_id, preferences.email;
COMMIT;

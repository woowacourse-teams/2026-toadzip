ALTER TABLE notification_subscriptions
    ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE;
UPDATE notification_subscriptions SET expires_at = updated_at + INTERVAL '12 months';
ALTER TABLE notification_subscriptions ALTER COLUMN expires_at SET NOT NULL;

ALTER TABLE notification_guest_subscriptions
    ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE;
UPDATE notification_guest_subscriptions SET expires_at = updated_at + INTERVAL '12 months';
ALTER TABLE notification_guest_subscriptions ALTER COLUMN expires_at SET NOT NULL;

-- Event email was not needed for interest metrics and must not remain in old rows.
ALTER TABLE notification_interest_events DROP COLUMN email;

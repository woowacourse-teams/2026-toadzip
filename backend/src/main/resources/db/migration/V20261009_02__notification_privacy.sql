ALTER TABLE users ADD COLUMN notification_settings_revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE notification_subscriptions
    ADD COLUMN notice_version VARCHAR(100),
    ADD COLUMN requested_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN purge_after TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_notification_subscriptions_purge_after ON notification_subscriptions (purge_after, id);

ALTER TABLE notification_interest_events
    ALTER COLUMN session_id DROP NOT NULL,
    ADD COLUMN user_id BIGINT REFERENCES users (id) ON DELETE CASCADE,
    ADD COLUMN notice_version VARCHAR(100),
    ADD COLUMN settings_revision BIGINT,
    ADD CONSTRAINT ck_notification_member_receipt CHECK (
        user_id IS NULL OR (event_type IN ('CONFIRMED', 'CANCELLED')
            AND session_id IS NULL AND settings_revision IS NOT NULL AND notice_version IS NOT NULL)
    );

CREATE INDEX idx_notification_interest_events_retention ON notification_interest_events (created_at, id);
CREATE INDEX idx_notification_interest_events_user ON notification_interest_events (user_id);

-- This feature has no pre-existing consent data. Do not invent notice versions or historical consent.

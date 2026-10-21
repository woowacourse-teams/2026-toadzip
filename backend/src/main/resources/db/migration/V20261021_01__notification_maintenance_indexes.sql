SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

CREATE INDEX IF NOT EXISTS idx_notification_outbox_statistics
    ON notification_outbox_events (status, occurred_at)
    WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX IF NOT EXISTS idx_notification_deliveries_statistics
    ON notification_deliveries (status, next_attempt_at)
    WHERE status IN ('PENDING', 'RETRY', 'SENDING', 'FAILED');

CREATE INDEX IF NOT EXISTS idx_notification_outbox_snapshot_expiry
    ON notification_outbox_events (occurred_at, id)
    WHERE status <> 'PENDING' AND target_snapshot IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_user_notifications_event
    ON user_notifications (event_id);

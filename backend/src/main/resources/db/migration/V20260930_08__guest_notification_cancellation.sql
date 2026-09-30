CREATE TABLE notification_guest_cancellation_requests (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    code_hash VARCHAR(64),
    code_expires_at TIMESTAMP WITH TIME ZONE,
    failed_attempts INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_notification_guest_cancellation_requested_at
    ON notification_guest_cancellation_requests (requested_at);

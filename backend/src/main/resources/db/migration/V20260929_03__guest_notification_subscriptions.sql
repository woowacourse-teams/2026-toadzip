CREATE TABLE notification_guest_email_preferences (
    client_id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE notification_guest_subscriptions (
    id BIGSERIAL PRIMARY KEY,
    client_id UUID NOT NULL REFERENCES notification_guest_email_preferences (client_id) ON DELETE CASCADE,
    target_type VARCHAR(20) NOT NULL CHECK (target_type IN ('REGION', 'COMPLEX', 'ANNOUNCEMENT')),
    target_id VARCHAR(19) NOT NULL,
    active BOOLEAN NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_notification_guest_subscription_target UNIQUE (client_id, target_type, target_id)
);

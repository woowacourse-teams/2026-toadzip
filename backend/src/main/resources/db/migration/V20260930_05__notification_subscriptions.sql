CREATE TABLE notification_email_preferences (
    user_id BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    email VARCHAR(254) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE notification_subscriptions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    target_type VARCHAR(20) NOT NULL CHECK (target_type IN ('REGION', 'COMPLEX', 'ANNOUNCEMENT')),
    target_id VARCHAR(19) NOT NULL,
    active BOOLEAN NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_notification_subscription_target UNIQUE (user_id, target_type, target_id)
);

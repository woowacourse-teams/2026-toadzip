-- Dedicated persistent add-on tables. Never alter, backfill or reference existing tables with foreign keys.
CREATE TABLE privacy_analytics_consents (
    id UUID PRIMARY KEY,
    user_id BIGINT UNIQUE,
    guest_token_hash VARCHAR(64) UNIQUE,
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('UNSET', 'GRANTED', 'DENIED', 'WITHDRAWN')),
    notice_version VARCHAR(80),
    scope_version VARCHAR(80),
    decided_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK ((user_id IS NULL) <> (guest_token_hash IS NULL)),
    CHECK (decision = 'UNSET' OR (notice_version IS NOT NULL AND scope_version IS NOT NULL AND decided_at IS NOT NULL)),
    CHECK ((guest_token_hash IS NULL AND decision <> 'GRANTED') OR expires_at IS NOT NULL)
);
CREATE INDEX privacy_analytics_consents_guest_expiry_idx ON privacy_analytics_consents (expires_at, id)
    WHERE guest_token_hash IS NOT NULL;

CREATE TABLE privacy_analytics_consent_events (
    id UUID PRIMARY KEY,
    consent_id UUID NOT NULL REFERENCES privacy_analytics_consents(id) ON DELETE CASCADE,
    command_id UUID NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    previous_decision VARCHAR(16) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    previous_revision BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    previous_notice_version VARCHAR(80),
    previous_scope_version VARCHAR(80),
    notice_version VARCHAR(80) NOT NULL,
    scope_version VARCHAR(80) NOT NULL,
    source VARCHAR(16) NOT NULL CHECK (source IN ('FIRST_VISIT', 'SETTINGS')),
    recorded_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    superseded_at TIMESTAMPTZ,
    purge_after TIMESTAMPTZ,
    UNIQUE (consent_id, command_id),
    UNIQUE (consent_id, revision)
);
CREATE INDEX privacy_analytics_consent_events_purge_idx ON privacy_analytics_consent_events (purge_after, id);

CREATE TABLE privacy_registration_notices (
    user_id BIGINT PRIMARY KEY,
    policy_version VARCHAR(100) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    purge_after TIMESTAMPTZ
);
CREATE INDEX privacy_registration_notices_purge_idx ON privacy_registration_notices (purge_after, user_id);

CREATE TABLE privacy_notification_states (
    user_id BIGINT PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE privacy_notification_notices (
    user_id BIGINT NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id VARCHAR(100) NOT NULL,
    notice_version VARCHAR(100) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    purge_after TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, target_type, target_id)
);
CREATE INDEX privacy_notification_notices_purge_idx ON privacy_notification_notices (purge_after, user_id);

CREATE TABLE privacy_notification_receipts (
    event_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL,
    source VARCHAR(30) NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    target_type VARCHAR(30) NOT NULL,
    target_id VARCHAR(100) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    outcome VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    settings_revision BIGINT NOT NULL,
    notice_version VARCHAR(100) NOT NULL,
    purge_after TIMESTAMPTZ NOT NULL
);
CREATE INDEX privacy_notification_receipts_purge_idx ON privacy_notification_receipts (purge_after, event_id);
CREATE INDEX privacy_notification_receipts_user_idx ON privacy_notification_receipts (user_id);

CREATE TABLE privacy_notification_events (
    event_id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    source VARCHAR(30) NOT NULL,
    target_type VARCHAR(30) NOT NULL,
    target_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    outcome VARCHAR(30) NOT NULL,
    purge_after TIMESTAMPTZ NOT NULL
);
CREATE INDEX privacy_notification_events_purge_idx ON privacy_notification_events (purge_after, event_id);

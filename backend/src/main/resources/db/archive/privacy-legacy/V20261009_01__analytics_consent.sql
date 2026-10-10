CREATE TABLE analytics_consents (
    id UUID PRIMARY KEY,
    user_id BIGINT UNIQUE REFERENCES users(id) ON DELETE CASCADE,
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
CREATE INDEX analytics_consents_guest_expiry_idx ON analytics_consents (expires_at, id)
    WHERE guest_token_hash IS NOT NULL;

CREATE TABLE analytics_consent_events (
    id UUID PRIMARY KEY,
    consent_id UUID NOT NULL REFERENCES analytics_consents(id) ON DELETE CASCADE,
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
CREATE INDEX analytics_consent_events_purge_idx ON analytics_consent_events (purge_after, id);

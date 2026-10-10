ALTER TABLE notification_interest_events
    ADD COLUMN request_fingerprint VARCHAR(64),
    ADD COLUMN outcome VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (outcome IN ('ACTIVATED', 'ALREADY_ACTIVE', 'NOT_ACTIVATED',
            'CANCELLED', 'UNCHANGED', 'OBSERVED', 'UNKNOWN'));

-- Existing events do not prove that a subscription changed. Do not backfill success.
-- The fingerprint detects command reuse without retaining the submitted email.

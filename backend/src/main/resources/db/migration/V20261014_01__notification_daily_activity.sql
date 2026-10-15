-- Operational totals only: no member, target, email, or event identifiers.
CREATE TABLE notification_daily_activity (
    day DATE PRIMARY KEY,
    activations BIGINT NOT NULL CHECK (activations >= 0),
    cancellations BIGINT NOT NULL CHECK (cancellations >= 0),
    first_recorded_at TIMESTAMPTZ NOT NULL
);

-- Collection starts now. Do not infer historical commands from current subscriptions.
INSERT INTO notification_daily_activity(day, activations, cancellations, first_recorded_at)
VALUES ((CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date, 0, 0, CURRENT_TIMESTAMP);

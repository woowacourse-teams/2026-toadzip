-- Existing notification requests do not prove that a new privacy notice was shown.
-- Preserve their notice_version/requested_at and calculate only their retention deadline.
-- Match Java Duration.ofDays(90), including databases whose timezone observes daylight saving time.
-- Legacy cancellation could update an already expired row; it must not extend retention past expiry + 90 days.
UPDATE notification_subscriptions
SET purge_after = CASE
    WHEN active THEN expires_at + INTERVAL '2160 hours'
    ELSE LEAST(updated_at, expires_at) + INTERVAL '2160 hours'
END
WHERE purge_after IS NULL;

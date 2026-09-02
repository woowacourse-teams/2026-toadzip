BEGIN;

ALTER TABLE myhome_announcement_source
    ADD COLUMN IF NOT EXISTS last_seen_run_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS consecutive_miss_count INTEGER,
    ADD COLUMN IF NOT EXISTS active BOOLEAN;

UPDATE myhome_announcement_source
SET consecutive_miss_count = COALESCE(consecutive_miss_count, 0),
    active = COALESCE(active, TRUE);

ALTER TABLE myhome_announcement_source
    ALTER COLUMN consecutive_miss_count SET DEFAULT 0,
    ALTER COLUMN consecutive_miss_count SET NOT NULL,
    ALTER COLUMN active SET DEFAULT TRUE,
    ALTER COLUMN active SET NOT NULL;

COMMIT;

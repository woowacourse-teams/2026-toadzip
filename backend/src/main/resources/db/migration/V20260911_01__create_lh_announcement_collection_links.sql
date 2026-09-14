CREATE TABLE IF NOT EXISTS lh_announcement_collection_links (
    id BIGSERIAL PRIMARY KEY,
    source VARCHAR(40) NOT NULL,
    source_announcement_key VARCHAR(500) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    request_description VARCHAR(2000) NOT NULL,
    pan_id VARCHAR(100) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_lh_announcement_link_source_announcement
        UNIQUE (source, source_announcement_key)
);

CREATE INDEX IF NOT EXISTS idx_lh_announcement_link_source_request_hash
    ON lh_announcement_collection_links (source, request_hash);

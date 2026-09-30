CREATE TABLE notification_interest_events (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    session_id UUID NOT NULL,
    event_type VARCHAR(20) NOT NULL CHECK (event_type IN ('EXPOSED', 'CLICKED', 'CONFIRMED', 'DECLINED')),
    source VARCHAR(30) NOT NULL CHECK (source IN ('SETTING', 'REGION_SEARCH', 'COMPLEX_DETAIL', 'ANNOUNCEMENT_DETAIL')),
    target_type VARCHAR(20) NOT NULL CHECK (target_type IN ('REGION', 'COMPLEX', 'ANNOUNCEMENT')),
    target_id VARCHAR(19) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

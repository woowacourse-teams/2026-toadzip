CREATE TABLE housing_complex_reviews (
    id BIGSERIAL PRIMARY KEY,
    housing_complex_id BIGINT NOT NULL REFERENCES housing_complexes(id) ON DELETE CASCADE,
    outcome VARCHAR(20) NOT NULL CHECK (outcome IN ('VERIFIED', 'ON_HOLD')),
    checked_values JSONB NOT NULL CHECK (jsonb_typeof(checked_values) = 'object' AND checked_values <> '{}'::jsonb),
    evidence_url VARCHAR(2048),
    evidence_note VARCHAR(2000) NOT NULL CHECK (length(trim(evidence_note)) > 0),
    actor VARCHAR(255) NOT NULL,
    reviewed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_housing_complex_reviews_latest ON housing_complex_reviews(housing_complex_id, id DESC);

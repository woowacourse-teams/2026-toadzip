CREATE TABLE myhome_complex_merges (
    id UUID PRIMARY KEY,
    representative_id BIGINT NOT NULL,
    snapshot_version INTEGER NOT NULL CHECK (snapshot_version = 1),
    adopted_household_count INTEGER NOT NULL CHECK (adopted_household_count > 0),
    complex_ids JSONB NOT NULL,
    before_state JSONB NOT NULL,
    after_state JSONB,
    evidence JSONB NOT NULL,
    preview_hash VARCHAR(64) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    verified_by VARCHAR(255) NOT NULL,
    merged_at TIMESTAMPTZ NOT NULL,
    reverted_at TIMESTAMPTZ,
    reverted_by VARCHAR(255),
    CONSTRAINT ck_complex_merge_reversion CHECK ((reverted_at IS NULL) = (reverted_by IS NULL))
);

CREATE TABLE myhome_complex_links (
    source_complex_identifier VARCHAR(255) PRIMARY KEY,
    housing_complex_id BIGINT NOT NULL REFERENCES housing_complexes(id) ON DELETE CASCADE,
    merge_id UUID REFERENCES myhome_complex_merges(id),
    approved_household_count INTEGER,
    CONSTRAINT ck_complex_link_approval CHECK (
        (merge_id IS NULL AND approved_household_count IS NULL)
        OR (merge_id IS NOT NULL AND approved_household_count IS NOT NULL AND approved_household_count > 0)
    )
);
CREATE INDEX ix_myhome_complex_link_target ON myhome_complex_links(housing_complex_id);

INSERT INTO myhome_complex_links (source_complex_identifier, housing_complex_id)
SELECT source_complex_identifier, id FROM housing_complexes
WHERE source_complex_identifier ~ '^[0-9]+:[A-Z0-9_]+$';

CREATE TABLE housing_complex_aliases (
    id BIGINT PRIMARY KEY,
    housing_complex_id BIGINT NOT NULL REFERENCES housing_complexes(id) ON DELETE CASCADE,
    merge_id UUID NOT NULL REFERENCES myhome_complex_merges(id),
    CONSTRAINT ck_complex_alias_target CHECK (id <> housing_complex_id)
);
CREATE INDEX ix_housing_complex_alias_target ON housing_complex_aliases(housing_complex_id);

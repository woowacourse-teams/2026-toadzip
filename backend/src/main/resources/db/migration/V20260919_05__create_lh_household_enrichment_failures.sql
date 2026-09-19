CREATE TABLE lh_household_enrichment_failures (
    id BIGSERIAL PRIMARY KEY,
    source_key VARCHAR(500) NOT NULL,
    area_name VARCHAR(200),
    supply_type_name VARCHAR(200),
    complex_name VARCHAR(500),
    reason VARCHAR(40) NOT NULL,
    detail VARCHAR(1000) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    occurrence_count INTEGER NOT NULL,
    recurrence_count INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    last_resolved_at TIMESTAMP WITH TIME ZONE,
    first_execution_id UUID,
    last_execution_id UUID,
    last_resolved_execution_id UUID
);

CREATE INDEX idx_lh_household_enrichment_failures_status_source
    ON lh_household_enrichment_failures (status, source_key);

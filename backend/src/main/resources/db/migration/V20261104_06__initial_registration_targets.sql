CREATE TABLE initial_registration_targets (
    source_complex_identifier varchar(255) PRIMARY KEY,
    name varchar(255),
    road_address varchar(500),
    status varchar(30) NOT NULL,
    detail text NOT NULL,
    housing_complex_id bigint,
    checked_at timestamptz NOT NULL,
    CONSTRAINT initial_registration_status_check CHECK (
        status IN ('EXISTING', 'LOCATION_REQUIRED', 'INFORMATION_REQUIRED', 'READY', 'FAILED', 'COMPLETED')
    ),
    CONSTRAINT initial_registration_completed_link_check CHECK (
        status NOT IN ('EXISTING', 'COMPLETED') OR housing_complex_id IS NOT NULL
    )
);

CREATE INDEX idx_initial_registration_status ON initial_registration_targets (status, source_complex_identifier);

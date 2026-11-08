ALTER TABLE initial_registration_targets
    ADD COLUMN location_source_address text,
    ADD COLUMN prepared_address text,
    ADD COLUMN prepared_latitude numeric(10, 8),
    ADD COLUMN prepared_longitude numeric(11, 8),
    ADD COLUMN location_origin varchar(10),
    ADD COLUMN location_failure varchar(40),
    ADD COLUMN location_checked_at timestamptz,
    ADD COLUMN location_checked_by varchar(255),
    ADD CONSTRAINT chk_initial_registration_location CHECK (
        (prepared_address IS NULL AND prepared_latitude IS NULL AND prepared_longitude IS NULL
            AND location_origin IS NULL)
        OR (prepared_address IS NOT NULL AND prepared_latitude BETWEEN -90 AND 90
            AND prepared_latitude IS NOT NULL AND prepared_longitude BETWEEN -180 AND 180
            AND prepared_longitude IS NOT NULL AND location_origin IN ('ZIP', 'ADMIN')
            AND location_origin IS NOT NULL AND location_failure IS NULL)
    );

ALTER TABLE housing_complexes
    DROP CONSTRAINT ck_complex_deposit_range,
    ADD CONSTRAINT ck_complex_deposit_range CHECK (
        (deposit_min IS NULL AND deposit_max IS NULL)
        OR (deposit_min IS NOT NULL AND deposit_max IS NOT NULL
            AND deposit_min >= 0 AND deposit_max >= deposit_min)
    ),
    DROP CONSTRAINT ck_complex_monthly_rent_range,
    ADD CONSTRAINT ck_complex_monthly_rent_range CHECK (
        (monthly_rent_min IS NULL AND monthly_rent_max IS NULL)
        OR (monthly_rent_min IS NOT NULL AND monthly_rent_max IS NOT NULL
            AND monthly_rent_min >= 0 AND monthly_rent_max >= monthly_rent_min)
    );

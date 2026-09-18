BEGIN;

ALTER TABLE announcements
    ADD COLUMN IF NOT EXISTS lh_reception_place_owned BOOLEAN;

UPDATE announcements
SET lh_reception_place_owned = CASE
    WHEN lh_pan_id IS NOT NULL AND reception_method IN ('VISIT', '현장') THEN TRUE
    ELSE FALSE
END
WHERE lh_reception_place_owned IS NULL;

ALTER TABLE announcements
    ALTER COLUMN lh_reception_place_owned SET DEFAULT FALSE,
    ALTER COLUMN lh_reception_place_owned SET NOT NULL;

ALTER TABLE supply_rows
    ADD COLUMN IF NOT EXISTS lh_total_supply_household_count_owned BOOLEAN;

UPDATE supply_rows
SET lh_total_supply_household_count_owned = CASE
    WHEN lh_source_supply_row_identifier IS NOT NULL
        AND total_supply_household_count IS NOT NULL THEN TRUE
    ELSE FALSE
END
WHERE lh_total_supply_household_count_owned IS NULL;

ALTER TABLE supply_rows
    ALTER COLUMN lh_total_supply_household_count_owned SET DEFAULT FALSE,
    ALTER COLUMN lh_total_supply_household_count_owned SET NOT NULL;

COMMIT;

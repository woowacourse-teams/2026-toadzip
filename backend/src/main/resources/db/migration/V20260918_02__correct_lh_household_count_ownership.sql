BEGIN;

ALTER TABLE supply_rows
    ADD COLUMN IF NOT EXISTS lh_total_supply_household_count_enriched BOOLEAN;

UPDATE supply_rows AS supply_row
SET lh_total_supply_household_count_owned = EXISTS (
    SELECT 1
    FROM lh_announcement_supply_source AS source
    WHERE supply_row.lh_source_supply_row_identifier =
        'LH:' || source.pan_id || ':SUPPLY:' || source.source_order
      AND (
          (
              REPLACE(BTRIM(source.total_unit_count), ',', '') ~ '^[0-9]+$'
              AND REPLACE(BTRIM(source.total_unit_count), ',', '')::NUMERIC =
                  supply_row.total_supply_household_count
          )
          OR (
              REPLACE(BTRIM(source.supplied_unit_count), ',', '') ~ '^[0-9]+$'
              AND REPLACE(BTRIM(source.supplied_unit_count), ',', '')::NUMERIC =
                  supply_row.total_supply_household_count
          )
      )
)
WHERE supply_row.lh_source_supply_row_identifier IS NOT NULL
  AND EXISTS (
      SELECT 1
      FROM lh_announcement_supply_source AS source
      WHERE supply_row.lh_source_supply_row_identifier =
          'LH:' || source.pan_id || ':SUPPLY:' || source.source_order
  );

UPDATE supply_rows AS supply_row
SET lh_total_supply_household_count_enriched = CASE
    WHEN source.id IS NULL THEN supply_row.lh_total_supply_household_count_owned
    ELSE REPLACE(BTRIM(source.total_unit_count), ',', '') ~ '^[0-9]+$'
        AND REPLACE(BTRIM(source.total_unit_count), ',', '')::NUMERIC =
            supply_row.total_supply_household_count
    END
FROM (
    SELECT row.id AS supply_row_id, source.id, source.total_unit_count
    FROM supply_rows AS row
    LEFT JOIN lh_announcement_supply_source AS source
      ON row.lh_source_supply_row_identifier =
          'LH:' || source.pan_id || ':SUPPLY:' || source.source_order
) AS source
WHERE supply_row.id = source.supply_row_id
  AND supply_row.lh_total_supply_household_count_enriched IS NULL;

UPDATE supply_rows
SET lh_total_supply_household_count_enriched = FALSE
WHERE lh_total_supply_household_count_enriched IS NULL;

ALTER TABLE supply_rows
    ALTER COLUMN lh_total_supply_household_count_enriched SET DEFAULT FALSE,
    ALTER COLUMN lh_total_supply_household_count_enriched SET NOT NULL;

COMMIT;

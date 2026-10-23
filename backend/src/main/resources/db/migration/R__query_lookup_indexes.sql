-- Keep hotfix indexes independent of unreleased versioned migrations on develop.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

CREATE INDEX IF NOT EXISTS idx_supply_rows_announcement_order
    ON supply_rows (announcement_id, display_order, id);

CREATE INDEX IF NOT EXISTS idx_supply_rows_complex_announcement
    ON supply_rows (housing_complex_id, announcement_id);

CREATE INDEX IF NOT EXISTS idx_supply_targets_row_order
    ON supply_targets (supply_row_id, display_order, id);

CREATE INDEX IF NOT EXISTS idx_housing_complexes_pnu_supply_type
    ON housing_complexes (pnu, supply_type)
    WHERE admin_deleted = false;

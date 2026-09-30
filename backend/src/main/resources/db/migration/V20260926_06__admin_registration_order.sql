-- Existing registration timestamps were not recorded; preserve unknown values as NULL.
ALTER TABLE housing_complexes ADD COLUMN created_at TIMESTAMPTZ;
ALTER TABLE housing_complexes ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE announcements ADD COLUMN created_at TIMESTAMPTZ;
ALTER TABLE announcements ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;
CREATE INDEX idx_housing_complexes_admin_registration
    ON housing_complexes (admin_deleted, created_at DESC NULLS LAST, id DESC);
CREATE INDEX idx_announcements_admin_registration
    ON announcements (admin_deleted, created_at DESC NULLS LAST, id DESC);

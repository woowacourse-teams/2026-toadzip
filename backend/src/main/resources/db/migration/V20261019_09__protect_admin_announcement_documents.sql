ALTER TABLE announcement_schedules ADD COLUMN admin_modified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE announcement_attachments ADD COLUMN admin_modified BOOLEAN NOT NULL DEFAULT FALSE;

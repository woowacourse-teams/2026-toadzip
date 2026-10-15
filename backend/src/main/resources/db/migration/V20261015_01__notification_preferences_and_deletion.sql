ALTER TABLE notification_push_devices ADD COLUMN resume_after_login BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE user_notifications ADD COLUMN deleted_at TIMESTAMPTZ;

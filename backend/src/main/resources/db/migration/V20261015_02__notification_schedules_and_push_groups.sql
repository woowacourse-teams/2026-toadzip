ALTER TABLE user_notifications ADD COLUMN content TEXT;
ALTER TABLE notification_deliveries ADD COLUMN group_id BIGINT;
CREATE INDEX idx_notification_delivery_group ON notification_deliveries(group_id) WHERE group_id IS NOT NULL;
CREATE INDEX idx_notification_schedule_start ON announcement_schedules(start_at, announcement_id);
CREATE INDEX idx_notification_schedule_end ON announcement_schedules(end_at, announcement_id);
CREATE INDEX idx_notification_application_start ON announcement_application_schedules(start_date, announcement_id);
CREATE INDEX idx_notification_application_end ON announcement_application_schedules(end_date, announcement_id);

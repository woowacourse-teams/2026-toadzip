ALTER TABLE notification_guest_cancellation_requests
    ADD COLUMN code_sent_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN code_sent_by VARCHAR(254);

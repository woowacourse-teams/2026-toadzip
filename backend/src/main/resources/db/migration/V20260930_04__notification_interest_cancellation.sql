ALTER TABLE notification_interest_events
    DROP CONSTRAINT notification_interest_events_event_type_check;

ALTER TABLE notification_interest_events
    ADD CONSTRAINT notification_interest_events_event_type_check
        CHECK (event_type IN ('EXPOSED', 'CLICKED', 'CONFIRMED', 'DECLINED', 'CANCELLED'));

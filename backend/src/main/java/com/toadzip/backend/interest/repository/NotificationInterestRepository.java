package com.toadzip.backend.interest.repository;

import com.toadzip.backend.interest.domain.NotificationInterestEvent;
import java.sql.Timestamp;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotificationInterestRepository {

    private final JdbcTemplate jdbcTemplate;

    public void record(NotificationInterestEvent event) {
        jdbcTemplate.update("""
                INSERT INTO notification_interest_events
                    (event_id, session_id, event_type, source, target_type, target_id, email, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """,
                event.getEventId(), event.getSessionId(), event.getEventType().name(), event.getSource().name(),
                event.getTargetType().name(), event.getTargetId(), event.getEmail(),
                Timestamp.from(event.getCreatedAt()));
    }
}

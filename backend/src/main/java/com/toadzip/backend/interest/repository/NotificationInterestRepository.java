package com.toadzip.backend.interest.repository;

import com.toadzip.backend.interest.domain.NotificationEventSource;
import com.toadzip.backend.interest.domain.NotificationEventType;
import com.toadzip.backend.interest.domain.NotificationInterestEvent;
import com.toadzip.backend.interest.domain.NotificationInterestOutcome;
import com.toadzip.backend.interest.domain.NotificationTargetType;
import java.sql.Timestamp;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotificationInterestRepository {

    private final JdbcTemplate jdbcTemplate;

    public boolean record(NotificationInterestEvent event) {
        return jdbcTemplate.update("""
                INSERT INTO notification_interest_events
                    (event_id, session_id, event_type, source, target_type, target_id, created_at, request_fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """,
                event.getEventId(), event.getSessionId(), event.getEventType().name(), event.getSource().name(),
                event.getTargetType().name(), event.getTargetId(),
                Timestamp.from(event.getCreatedAt()), event.getRequestFingerprint()) > 0;
    }

    public NotificationInterestEvent findForUpdate(UUID eventId) {
        return jdbcTemplate.query("""
                SELECT event_id, session_id, event_type, source, target_type, target_id, created_at,
                    request_fingerprint, outcome
                FROM notification_interest_events WHERE event_id = ? FOR UPDATE
                """, (row, number) -> NotificationInterestEvent.restore(
                row.getObject("event_id", UUID.class), row.getObject("session_id", UUID.class),
                NotificationEventType.valueOf(row.getString("event_type")),
                NotificationEventSource.valueOf(row.getString("source")),
                NotificationTargetType.valueOf(row.getString("target_type")), row.getString("target_id"),
                row.getTimestamp("created_at").toInstant(), row.getString("request_fingerprint"),
                NotificationInterestOutcome.valueOf(row.getString("outcome"))), eventId).stream().findFirst()
                .orElseThrow(() -> new ConcurrencyFailureException("알림 요청 결과가 제거되었습니다."));
    }

    public void complete(NotificationInterestEvent event) {
        int updated = jdbcTemplate.update("UPDATE notification_interest_events SET outcome = ? WHERE event_id = ?",
                event.getOutcome().name(), event.getEventId());
        if (updated != 1) {
            throw new ConcurrencyFailureException("알림 요청 결과를 저장할 수 없습니다.");
        }
    }
}

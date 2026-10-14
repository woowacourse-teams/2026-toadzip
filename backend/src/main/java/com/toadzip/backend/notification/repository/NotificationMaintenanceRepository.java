package com.toadzip.backend.notification.repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotificationMaintenanceRepository {
    private final JdbcTemplate jdbc;

    public void purgeDeliveries(Instant now) {
        jdbc.update("""
                WITH expired AS (
                    SELECT id FROM notification_deliveries
                    WHERE (status IN ('SENT','SKIPPED') AND finished_at<?)
                        OR (status='FAILED' AND finished_at<?)
                    ORDER BY finished_at,id LIMIT 500 FOR UPDATE SKIP LOCKED)
                DELETE FROM notification_deliveries d USING expired e WHERE d.id=e.id
                """, Timestamp.from(now.minus(Duration.ofDays(30))), Timestamp.from(now.minus(Duration.ofDays(90))));
    }

    public void purgeInbox(Instant now) {
        jdbc.update("""
                WITH expired AS (
                    SELECT n.id FROM user_notifications n WHERE n.expires_at<=?
                        AND NOT EXISTS(SELECT 1 FROM notification_deliveries d WHERE d.notification_id=n.id)
                    ORDER BY n.expires_at,n.id LIMIT 500 FOR UPDATE OF n SKIP LOCKED)
                DELETE FROM user_notifications n USING expired e WHERE n.id=e.id
                """, Timestamp.from(now));
    }

    public void trimEvents(Instant now) {
        jdbc.update("""
                WITH expired AS (
                    SELECT e.id FROM notification_outbox_events e
                    WHERE e.status<>'PENDING' AND e.occurred_at<? AND e.target_snapshot IS NOT NULL
                        AND NOT EXISTS(SELECT 1 FROM user_notifications n WHERE n.event_id=e.id)
                    ORDER BY e.occurred_at,e.id LIMIT 500 FOR UPDATE OF e SKIP LOCKED)
                UPDATE notification_outbox_events e SET target_snapshot=NULL FROM expired old WHERE e.id=old.id
                """, Timestamp.from(now.minus(Duration.ofDays(90))));
    }

    public List<QueueStatistics> statistics(Instant now) {
        return jdbc.query("""
                SELECT 'outbox' AS queue,count(*) FILTER(WHERE status='PENDING') AS pending,
                    count(*) FILTER(WHERE status='FAILED') AS failed,
                    COALESCE(GREATEST(EXTRACT(EPOCH FROM (? - min(occurred_at)
                        FILTER(WHERE status='PENDING'))),0),0) AS delay FROM notification_outbox_events
                UNION ALL
                SELECT 'delivery',count(*) FILTER(WHERE status IN ('PENDING','RETRY','SENDING')),
                    count(*) FILTER(WHERE status='FAILED'),
                    COALESCE(GREATEST(EXTRACT(EPOCH FROM (? - min(next_attempt_at)
                        FILTER(WHERE status IN ('PENDING','RETRY','SENDING')))),0),0) FROM notification_deliveries
                """, (row, number) -> new QueueStatistics(row.getString("queue"), row.getLong("pending"),
                row.getLong("failed"), row.getLong("delay")), Timestamp.from(now), Timestamp.from(now));
    }

    public record QueueStatistics(String queue, long pending, long failed, long delay) {
    }
}

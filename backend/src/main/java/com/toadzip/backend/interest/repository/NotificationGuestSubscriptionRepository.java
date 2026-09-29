package com.toadzip.backend.interest.repository;

import com.toadzip.backend.interest.domain.NotificationTargetType;
import com.toadzip.backend.interest.dto.NotificationSubscriptionResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotificationGuestSubscriptionRepository {

    private final JdbcTemplate jdbcTemplate;

    public void confirm(UUID clientId, NotificationTargetType type, String id, String email, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO notification_guest_email_preferences (client_id, email, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT (client_id) DO UPDATE SET email = EXCLUDED.email, updated_at = EXCLUDED.updated_at
                """, clientId, email, Timestamp.from(now));
        activate(clientId, type, id, now);
    }

    public boolean hasEmail(UUID clientId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM notification_guest_email_preferences preferences
                WHERE preferences.client_id = ? AND EXISTS (
                    SELECT 1 FROM notification_guest_subscriptions subscriptions
                    WHERE subscriptions.client_id = preferences.client_id
                      AND subscriptions.active = true AND subscriptions.expires_at > CURRENT_TIMESTAMP)
                """, Integer.class, clientId);
        return count != null && count > 0;
    }

    public void activate(UUID clientId, NotificationTargetType type, String id, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO notification_guest_subscriptions
                    (client_id, target_type, target_id, active, updated_at, expires_at)
                VALUES (?, ?, ?, true, ?, ?::timestamptz + INTERVAL '12 months')
                ON CONFLICT ON CONSTRAINT uk_notification_guest_subscription_target
                DO UPDATE SET active = true, updated_at = EXCLUDED.updated_at, expires_at = EXCLUDED.expires_at
                """, clientId, type.name(), id, Timestamp.from(now), Timestamp.from(now));
    }

    public void cancel(UUID clientId, NotificationTargetType type, String id, Instant now) {
        jdbcTemplate.update("""
                UPDATE notification_guest_subscriptions
                SET active = false, updated_at = ?
                WHERE client_id = ? AND target_type = ? AND target_id = ?
                """, Timestamp.from(now), clientId, type.name(), id);
        jdbcTemplate.update("""
                DELETE FROM notification_guest_email_preferences preferences
                WHERE preferences.client_id = ? AND NOT EXISTS (
                    SELECT 1 FROM notification_guest_subscriptions subscriptions
                    WHERE subscriptions.client_id = preferences.client_id
                      AND subscriptions.active = true AND subscriptions.expires_at > CURRENT_TIMESTAMP)
                """, clientId);
    }

    public NotificationSubscriptionResponse findForClient(UUID clientId) {
        List<NotificationSubscriptionResponse.Target> targets = jdbcTemplate.query("""
                SELECT target_type, target_id FROM notification_guest_subscriptions
                WHERE client_id = ? AND active = true AND expires_at > CURRENT_TIMESTAMP
                ORDER BY target_type, target_id
                """, (row, number) -> new NotificationSubscriptionResponse.Target(
                NotificationTargetType.valueOf(row.getString("target_type")), row.getString("target_id")), clientId);
        return new NotificationSubscriptionResponse(hasEmail(clientId), targets);
    }
}

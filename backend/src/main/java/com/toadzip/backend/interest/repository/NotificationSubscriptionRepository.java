package com.toadzip.backend.interest.repository;

import com.toadzip.backend.interest.domain.NotificationTargetType;
import com.toadzip.backend.interest.dto.NotificationSubscriptionResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotificationSubscriptionRepository {

    private final JdbcTemplate jdbcTemplate;

    public void confirm(long userId, NotificationTargetType type, String id, String email, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO notification_email_preferences (user_id, email, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET email = EXCLUDED.email, updated_at = EXCLUDED.updated_at
                """, userId, email, Timestamp.from(now));
        activate(userId, type, id, now);
    }

    public boolean hasEmail(long userId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM notification_email_preferences preferences
                WHERE preferences.user_id = ? AND EXISTS (
                    SELECT 1 FROM notification_subscriptions subscriptions
                    WHERE subscriptions.user_id = preferences.user_id
                      AND subscriptions.active = true AND subscriptions.expires_at > CURRENT_TIMESTAMP)
                """, Integer.class, userId);
        return count != null && count > 0;
    }

    public void activate(long userId, NotificationTargetType type, String id, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO notification_subscriptions (user_id, target_type, target_id, active, updated_at, expires_at)
                VALUES (?, ?, ?, true, ?, ?::timestamptz + INTERVAL '12 months')
                ON CONFLICT ON CONSTRAINT uk_notification_subscription_target
                DO UPDATE SET active = true, updated_at = EXCLUDED.updated_at, expires_at = EXCLUDED.expires_at
                """, userId, type.name(), id, Timestamp.from(now), Timestamp.from(now));
    }

    public void cancel(long userId, NotificationTargetType type, String id, Instant now) {
        jdbcTemplate.update("""
                UPDATE notification_subscriptions
                SET active = false, updated_at = ?
                WHERE user_id = ? AND target_type = ? AND target_id = ?
                """, Timestamp.from(now), userId, type.name(), id);
        jdbcTemplate.update("""
                DELETE FROM notification_email_preferences preferences
                WHERE preferences.user_id = ? AND NOT EXISTS (
                    SELECT 1 FROM notification_subscriptions subscriptions
                    WHERE subscriptions.user_id = preferences.user_id
                      AND subscriptions.active = true AND subscriptions.expires_at > CURRENT_TIMESTAMP)
                """, userId);
    }

    public NotificationSubscriptionResponse findForUser(long userId) {
        List<NotificationSubscriptionResponse.Target> targets = jdbcTemplate.query("""
                SELECT subscriptions.target_type, subscriptions.target_id,
                       COALESCE(complex.name, announcement.name) AS target_name
                FROM notification_subscriptions subscriptions
                LEFT JOIN housing_complexes complex
                    ON subscriptions.target_type = 'COMPLEX' AND subscriptions.target_id = complex.id::text
                LEFT JOIN announcements announcement
                    ON subscriptions.target_type = 'ANNOUNCEMENT' AND subscriptions.target_id = announcement.id::text
                WHERE subscriptions.user_id = ? AND subscriptions.active = true
                    AND subscriptions.expires_at > CURRENT_TIMESTAMP
                ORDER BY subscriptions.target_type, subscriptions.target_id
                """, (row, number) -> new NotificationSubscriptionResponse.Target(
                NotificationTargetType.valueOf(row.getString("target_type")), row.getString("target_id"),
                row.getString("target_name")), userId);
        return new NotificationSubscriptionResponse(hasEmail(userId), targets);
    }
}

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
                "SELECT count(*) FROM notification_email_preferences WHERE user_id = ?", Integer.class, userId);
        return count != null && count > 0;
    }

    public void activate(long userId, NotificationTargetType type, String id, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO notification_subscriptions (user_id, target_type, target_id, active, updated_at)
                VALUES (?, ?, ?, true, ?)
                ON CONFLICT ON CONSTRAINT uk_notification_subscription_target
                DO UPDATE SET active = true, updated_at = EXCLUDED.updated_at
                """, userId, type.name(), id, Timestamp.from(now));
    }

    public void cancel(long userId, NotificationTargetType type, String id, Instant now) {
        jdbcTemplate.update("""
                UPDATE notification_subscriptions
                SET active = false, updated_at = ?
                WHERE user_id = ? AND target_type = ? AND target_id = ?
                """, Timestamp.from(now), userId, type.name(), id);
    }

    public NotificationSubscriptionResponse findForUser(long userId) {
        List<NotificationSubscriptionResponse.Target> targets = jdbcTemplate.query("""
                SELECT target_type, target_id FROM notification_subscriptions
                WHERE user_id = ? AND active = true
                ORDER BY target_type, target_id
                """, (row, number) -> new NotificationSubscriptionResponse.Target(
                NotificationTargetType.valueOf(row.getString("target_type")), row.getString("target_id")), userId);
        return new NotificationSubscriptionResponse(hasEmail(userId), targets);
    }
}

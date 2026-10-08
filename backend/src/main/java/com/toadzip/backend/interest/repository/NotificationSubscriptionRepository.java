package com.toadzip.backend.interest.repository;

import com.toadzip.backend.interest.domain.NotificationInterestOutcome;
import com.toadzip.backend.interest.domain.NotificationSubscriptionState;
import com.toadzip.backend.interest.domain.NotificationTargetType;
import com.toadzip.backend.interest.dto.NotificationSubscriptionResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotificationSubscriptionRepository {

    private final JdbcTemplate jdbcTemplate;

    public NotificationInterestOutcome confirm(
            long userId, NotificationTargetType type, String id, String email, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO notification_email_preferences (user_id, email, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET email = EXCLUDED.email, updated_at = EXCLUDED.updated_at
                """, userId, email, Timestamp.from(now));
        return activate(userId, type, id, now);
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

    public NotificationInterestOutcome activate(
            long userId, NotificationTargetType type, String id, Instant now) {
        if (!lockEmailPreference(userId)) {
            return NotificationInterestOutcome.NOT_ACTIVATED;
        }
        List<Long> inserted = jdbcTemplate.query("""
                INSERT INTO notification_subscriptions
                    (user_id, target_type, target_id, active, updated_at, expires_at)
                VALUES (?, ?, ?, true, ?, ?::timestamptz + INTERVAL '12 months')
                ON CONFLICT ON CONSTRAINT uk_notification_subscription_target DO NOTHING
                RETURNING id
                """, (row, number) -> row.getLong("id"), userId, type.name(), id,
                Timestamp.from(now), Timestamp.from(now));
        if (!inserted.isEmpty()) {
            return NotificationInterestOutcome.ACTIVATED;
        }
        NotificationSubscriptionState previous = findForUpdate(userId, type, id)
                .orElseThrow(() -> new ConcurrencyFailureException("알림 신청이 제거되었습니다."));
        int updated = jdbcTemplate.update("""
                UPDATE notification_subscriptions
                SET active = true, updated_at = ?, expires_at = ?::timestamptz + INTERVAL '12 months'
                WHERE user_id = ? AND target_type = ? AND target_id = ?
                """, Timestamp.from(now), Timestamp.from(now), userId, type.name(), id);
        if (updated != 1) {
            throw new ConcurrencyFailureException("알림 신청을 갱신할 수 없습니다.");
        }
        return previous.activationOutcome(now);
    }

    public NotificationInterestOutcome cancel(
            long userId, NotificationTargetType type, String id, Instant now) {
        lockEmailPreference(userId);
        Optional<NotificationSubscriptionState> previous = findForUpdate(userId, type, id);
        if (previous.isEmpty()) {
            return NotificationInterestOutcome.UNCHANGED;
        }
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
        return previous.get().cancellationOutcome(now);
    }

    private boolean lockEmailPreference(long userId) {
        return !jdbcTemplate.query("""
                SELECT user_id FROM notification_email_preferences WHERE user_id = ? FOR UPDATE
                """, (row, number) -> row.getLong("user_id"), userId).isEmpty();
    }

    private Optional<NotificationSubscriptionState> findForUpdate(
            long userId, NotificationTargetType type, String id) {
        return jdbcTemplate.query("""
                SELECT active, expires_at FROM notification_subscriptions
                WHERE user_id = ? AND target_type = ? AND target_id = ? FOR UPDATE
                """, (row, number) -> new NotificationSubscriptionState(
                row.getBoolean("active"), row.getTimestamp("expires_at").toInstant()),
                userId, type.name(), id).stream().findFirst();
    }

    public NotificationSubscriptionResponse findForUser(long userId) {
        List<NotificationSubscriptionResponse.Target> targets = jdbcTemplate.query("""
                SELECT target_type, target_id FROM notification_subscriptions
                WHERE user_id = ? AND active = true AND expires_at > CURRENT_TIMESTAMP
                ORDER BY target_type, target_id
                """, (row, number) -> new NotificationSubscriptionResponse.Target(
                NotificationTargetType.valueOf(row.getString("target_type")), row.getString("target_id")), userId);
        return new NotificationSubscriptionResponse(hasEmail(userId), targets);
    }
}

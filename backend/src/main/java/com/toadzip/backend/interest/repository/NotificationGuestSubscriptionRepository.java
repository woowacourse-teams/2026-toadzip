package com.toadzip.backend.interest.repository;

import com.toadzip.backend.interest.domain.NotificationInterestOutcome;
import com.toadzip.backend.interest.domain.NotificationSubscriptionState;
import com.toadzip.backend.interest.domain.NotificationTargetType;
import com.toadzip.backend.interest.dto.NotificationSubscriptionResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotificationGuestSubscriptionRepository {

    private final JdbcTemplate jdbcTemplate;

    public NotificationInterestOutcome confirm(
            UUID clientId, NotificationTargetType type, String id, String email, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO notification_guest_email_preferences (client_id, email, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT (client_id) DO UPDATE SET email = EXCLUDED.email, updated_at = EXCLUDED.updated_at
                """, clientId, email, Timestamp.from(now));
        return activate(clientId, type, id, now);
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

    public NotificationInterestOutcome activate(
            UUID clientId, NotificationTargetType type, String id, Instant now) {
        if (!lockEmailPreference(clientId)) {
            return NotificationInterestOutcome.NOT_ACTIVATED;
        }
        List<Long> inserted = jdbcTemplate.query("""
                INSERT INTO notification_guest_subscriptions
                    (client_id, target_type, target_id, active, updated_at, expires_at)
                VALUES (?, ?, ?, true, ?, ?::timestamptz + INTERVAL '12 months')
                ON CONFLICT ON CONSTRAINT uk_notification_guest_subscription_target DO NOTHING
                RETURNING id
                """, (row, number) -> row.getLong("id"), clientId, type.name(), id,
                Timestamp.from(now), Timestamp.from(now));
        if (!inserted.isEmpty()) {
            return NotificationInterestOutcome.ACTIVATED;
        }
        NotificationSubscriptionState previous = findForUpdate(clientId, type, id)
                .orElseThrow(() -> new ConcurrencyFailureException("알림 신청이 제거되었습니다."));
        int updated = jdbcTemplate.update("""
                UPDATE notification_guest_subscriptions
                SET active = true, updated_at = ?, expires_at = ?::timestamptz + INTERVAL '12 months'
                WHERE client_id = ? AND target_type = ? AND target_id = ?
                """, Timestamp.from(now), Timestamp.from(now), clientId, type.name(), id);
        if (updated != 1) {
            throw new ConcurrencyFailureException("알림 신청을 갱신할 수 없습니다.");
        }
        return previous.activationOutcome(now);
    }

    public NotificationInterestOutcome cancel(
            UUID clientId, NotificationTargetType type, String id, Instant now) {
        lockEmailPreference(clientId);
        Optional<NotificationSubscriptionState> previous = findForUpdate(clientId, type, id);
        if (previous.isEmpty()) {
            return NotificationInterestOutcome.UNCHANGED;
        }
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
        jdbcTemplate.update("""
                DELETE FROM notification_guest_cancellation_requests requests
                WHERE NOT EXISTS (
                    SELECT 1 FROM notification_guest_email_preferences preferences
                    JOIN notification_guest_subscriptions subscriptions
                      ON subscriptions.client_id = preferences.client_id
                    WHERE lower(preferences.email) = requests.email
                      AND subscriptions.active = true AND subscriptions.expires_at > CURRENT_TIMESTAMP)
                """);
        return previous.get().cancellationOutcome(now);
    }

    private boolean lockEmailPreference(UUID clientId) {
        return !jdbcTemplate.query("""
                SELECT client_id FROM notification_guest_email_preferences WHERE client_id = ? FOR UPDATE
                """, (row, number) -> row.getObject("client_id", UUID.class), clientId).isEmpty();
    }

    private Optional<NotificationSubscriptionState> findForUpdate(
            UUID clientId, NotificationTargetType type, String id) {
        return jdbcTemplate.query("""
                SELECT active, expires_at FROM notification_guest_subscriptions
                WHERE client_id = ? AND target_type = ? AND target_id = ? FOR UPDATE
                """, (row, number) -> new NotificationSubscriptionState(
                row.getBoolean("active"), row.getTimestamp("expires_at").toInstant()),
                clientId, type.name(), id).stream().findFirst();
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

package com.toadzip.backend.interest.repository;

import com.toadzip.backend.privacy.domain.PrivacyRetentionPolicy;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotificationRetentionRepository {

    private final JdbcTemplate jdbcTemplate;

    public int purgeChunk(Instant now, int limit) {
        Instant eventThreshold = now.minus(PrivacyRetentionPolicy.NOTIFICATION_EVENT_RETENTION);
        List<Candidate> candidates = jdbcTemplate.query("""
                SELECT kind, id, user_id FROM (
                    SELECT 'SETTING' AS kind, id, user_id FROM notification_subscriptions WHERE purge_after <= ?
                    UNION ALL
                    SELECT 'EVENT' AS kind, id, user_id FROM notification_interest_events WHERE created_at <= ?
                ) candidates ORDER BY user_id NULLS LAST, kind, id LIMIT ?
                """, (row, number) -> new Candidate(row.getString("kind"), row.getLong("id"),
                row.getObject("user_id", Long.class)), Timestamp.from(now), Timestamp.from(eventThreshold), limit);
        int deleted = 0;
        for (Candidate candidate : candidates) {
            if (lockOwner(candidate)) {
                deleted += deleteCandidate(candidate, now, eventThreshold);
            }
        }
        int remaining = limit - deleted;
        if (remaining > 0) {
            deleted += purgeUnusedMemberEmails(now, remaining);
        }
        remaining = limit - deleted;
        if (remaining > 0) {
            deleted += purgeUnusedGuestData(now, remaining);
        }
        remaining = limit - deleted;
        if (remaining > 0) {
            deleted += purgeGuestCancellationRequests(now, remaining);
        }
        return deleted;
    }

    private boolean lockOwner(Candidate candidate) {
        if (candidate.userId() == null) {
            return true;
        }
        return !jdbcTemplate.query("SELECT id FROM users WHERE id = ? FOR UPDATE SKIP LOCKED",
                (row, number) -> row.getLong(1), candidate.userId()).isEmpty();
    }

    private int deleteCandidate(Candidate candidate, Instant now, Instant eventThreshold) {
        if ("SETTING".equals(candidate.kind())) {
            return jdbcTemplate.update("""
                    DELETE FROM notification_subscriptions WHERE id IN (
                        SELECT id FROM notification_subscriptions
                        WHERE id = ? AND purge_after <= ? FOR UPDATE SKIP LOCKED)
                    """, candidate.id(), Timestamp.from(now));
        }
        return jdbcTemplate.update("""
                DELETE FROM notification_interest_events WHERE id IN (
                    SELECT id FROM notification_interest_events
                    WHERE id = ? AND created_at <= ? FOR UPDATE SKIP LOCKED)
                """, candidate.id(), Timestamp.from(eventThreshold));
    }

    private int purgeUnusedMemberEmails(Instant now, int limit) {
        List<Long> owners = jdbcTemplate.query("""
                SELECT preferences.user_id FROM notification_email_preferences preferences
                WHERE NOT EXISTS (SELECT 1 FROM notification_subscriptions subscriptions
                    WHERE subscriptions.user_id = preferences.user_id AND subscriptions.active = true
                      AND subscriptions.expires_at > ?)
                ORDER BY preferences.user_id LIMIT ?
                """, (row, number) -> row.getLong(1), Timestamp.from(now), limit);
        int deleted = 0;
        for (Long owner : owners) {
            if (lockOwner(new Candidate("EMAIL", owner, owner))) {
                deleted += jdbcTemplate.update("""
                        DELETE FROM notification_email_preferences preferences WHERE preferences.user_id = ?
                        AND NOT EXISTS (SELECT 1 FROM notification_subscriptions subscriptions
                            WHERE subscriptions.user_id = preferences.user_id AND subscriptions.active = true
                              AND subscriptions.expires_at > ?)
                        """, owner, Timestamp.from(now));
            }
        }
        return deleted;
    }

    private int purgeUnusedGuestData(Instant now, int limit) {
        List<UUID> owners = jdbcTemplate.query("""
                SELECT preferences.client_id FROM notification_guest_email_preferences preferences
                WHERE NOT EXISTS (SELECT 1 FROM notification_guest_subscriptions subscriptions
                    WHERE subscriptions.client_id = preferences.client_id AND subscriptions.active = true
                      AND subscriptions.expires_at > ?)
                ORDER BY preferences.client_id LIMIT ? FOR UPDATE OF preferences SKIP LOCKED
                """, (row, number) -> row.getObject(1, UUID.class), Timestamp.from(now), limit);
        int deleted = 0;
        for (UUID owner : owners) {
            if (deleted == limit) {
                break;
            }
            // Lock the preference before its children, then recheck eligibility in a fresh statement.
            deleted += jdbcTemplate.update("""
                    DELETE FROM notification_guest_subscriptions WHERE id IN (
                        SELECT subscriptions.id FROM notification_guest_subscriptions subscriptions
                        WHERE subscriptions.client_id = ? AND NOT EXISTS (
                            SELECT 1 FROM notification_guest_subscriptions active_subscription
                            WHERE active_subscription.client_id = subscriptions.client_id
                              AND active_subscription.active = true AND active_subscription.expires_at > ?)
                        ORDER BY subscriptions.id LIMIT ? FOR UPDATE OF subscriptions SKIP LOCKED)
                    """, owner, Timestamp.from(now), limit - deleted);
            if (deleted < limit) {
                // An empty parent cannot cascade-delete extra rows beyond this transaction's budget.
                deleted += jdbcTemplate.update("""
                        DELETE FROM notification_guest_email_preferences preferences
                        WHERE preferences.client_id = ? AND NOT EXISTS (
                            SELECT 1 FROM notification_guest_subscriptions subscriptions
                            WHERE subscriptions.client_id = preferences.client_id)
                        """, owner);
            }
        }
        return deleted;
    }

    private int purgeGuestCancellationRequests(Instant now, int limit) {
        Instant threshold = now.minus(PrivacyRetentionPolicy.LEGACY_GUEST_CANCELLATION_RETENTION);
        // A skipped or partially purged owner keeps its cancellation path until all preferences are removed.
        return jdbcTemplate.update("""
                DELETE FROM notification_guest_cancellation_requests WHERE id IN (
                    SELECT requests.id FROM notification_guest_cancellation_requests requests
                    WHERE requests.requested_at <= ? OR NOT EXISTS (
                        SELECT 1 FROM notification_guest_email_preferences preferences
                        WHERE lower(preferences.email) = requests.email)
                    ORDER BY requests.requested_at, requests.id LIMIT ? FOR UPDATE OF requests SKIP LOCKED)
                """, Timestamp.from(threshold), limit);
    }

    public Backlog backlog(Instant now) {
        Instant eventThreshold = now.minus(PrivacyRetentionPolicy.NOTIFICATION_EVENT_RETENTION);
        Instant cancellationThreshold = now.minus(PrivacyRetentionPolicy.LEGACY_GUEST_CANCELLATION_RETENTION);
        // Orphan removal time is not recorded. Count those rows without inventing an overdue timestamp.
        return jdbcTemplate.queryForObject("""
                WITH unused_guest_preferences AS (
                    SELECT preferences.client_id, (
                        SELECT max(CASE WHEN subscriptions.active THEN subscriptions.expires_at
                            ELSE least(subscriptions.updated_at, subscriptions.expires_at) END)
                        FROM notification_guest_subscriptions subscriptions
                        WHERE subscriptions.client_id = preferences.client_id) AS due_at
                    FROM notification_guest_email_preferences preferences
                    WHERE NOT EXISTS (SELECT 1 FROM notification_guest_subscriptions subscriptions
                        WHERE subscriptions.client_id = preferences.client_id AND subscriptions.active = true
                          AND subscriptions.expires_at > ?)
                )
                SELECT count(*) AS remaining, min(due_at) AS oldest FROM (
                    SELECT purge_after AS due_at FROM notification_subscriptions WHERE purge_after <= ?
                    UNION ALL
                    SELECT created_at + (? * INTERVAL '1 second') AS due_at
                    FROM notification_interest_events WHERE created_at <= ?
                    UNION ALL
                    SELECT NULL::timestamptz AS due_at FROM notification_email_preferences preferences
                    WHERE NOT EXISTS (SELECT 1 FROM notification_subscriptions subscriptions
                        WHERE subscriptions.user_id = preferences.user_id AND subscriptions.active = true
                          AND subscriptions.expires_at > ?)
                    UNION ALL
                    SELECT due_at FROM unused_guest_preferences
                    UNION ALL
                    SELECT preferences.due_at FROM notification_guest_subscriptions subscriptions
                    JOIN unused_guest_preferences preferences ON preferences.client_id = subscriptions.client_id
                    UNION ALL
                    SELECT CASE WHEN requests.requested_at <= ?
                        THEN requests.requested_at + (? * INTERVAL '1 second') END AS due_at
                    FROM notification_guest_cancellation_requests requests
                    WHERE requests.requested_at <= ? OR NOT EXISTS (
                        SELECT 1 FROM notification_guest_email_preferences preferences
                        JOIN notification_guest_subscriptions subscriptions
                          ON subscriptions.client_id = preferences.client_id
                        WHERE lower(preferences.email) = requests.email AND subscriptions.active = true
                          AND subscriptions.expires_at > ?)
                ) expired
                """, (row, number) -> {
                    Timestamp oldest = row.getTimestamp("oldest");
                    long delay = 0;
                    if (oldest != null) {
                        delay = Math.max(0, Duration.between(oldest.toInstant(), now).getSeconds());
                    }
                    return new Backlog(row.getLong("remaining"), delay);
                }, Timestamp.from(now), Timestamp.from(now),
                PrivacyRetentionPolicy.NOTIFICATION_EVENT_RETENTION.toSeconds(), Timestamp.from(eventThreshold),
                Timestamp.from(now), Timestamp.from(cancellationThreshold),
                PrivacyRetentionPolicy.LEGACY_GUEST_CANCELLATION_RETENTION.toSeconds(),
                Timestamp.from(cancellationThreshold), Timestamp.from(now));
    }

    private record Candidate(String kind, long id, Long userId) {
    }

    public record Backlog(long count, long oldestOverdueSeconds) {
    }
}

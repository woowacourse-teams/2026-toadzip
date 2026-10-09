package com.toadzip.backend.privacy.repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Deletes only records owned by the privacy feature. Legacy notification records are never targets. */
@Repository
@RequiredArgsConstructor
public class PrivacyNotificationRetentionRepository {

    private final JdbcTemplate jdbcTemplate;

    public int purgeChunk(Instant now, int limit) {
        List<Candidate> candidates = jdbcTemplate.query("""
                SELECT kind, user_id, event_id, target_type, target_id FROM (
                    SELECT 'NOTICE' AS kind, user_id, NULL::uuid AS event_id, target_type, target_id
                    FROM privacy_notification_notices WHERE purge_after <= ?
                        OR NOT EXISTS (SELECT 1 FROM users WHERE id = user_id)
                    UNION ALL
                    SELECT 'RECEIPT', user_id, event_id, NULL, NULL FROM privacy_notification_receipts
                    WHERE purge_after <= ? OR NOT EXISTS (SELECT 1 FROM users WHERE id = user_id)
                    UNION ALL
                    SELECT 'STATE', user_id, NULL, NULL, NULL FROM privacy_notification_states
                    WHERE NOT EXISTS (SELECT 1 FROM users WHERE id = user_id)
                    UNION ALL
                    SELECT 'EVENT', NULL, event_id, NULL, NULL FROM privacy_notification_events WHERE purge_after <= ?
                ) candidates ORDER BY user_id NULLS LAST, kind, event_id, target_type, target_id LIMIT ?
                """, (row, number) -> new Candidate(row.getString("kind"), row.getObject("user_id", Long.class),
                row.getObject("event_id", UUID.class), row.getString("target_type"), row.getString("target_id")),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), limit);
        int deleted = 0;
        for (Candidate candidate : candidates) {
            if (lockOwner(candidate.userId())) {
                deleted += deleteCandidate(candidate, now);
            }
        }
        return deleted;
    }

    private boolean lockOwner(Long userId) {
        if (userId == null) {
            return true;
        }
        List<Long> owners = jdbcTemplate.query("SELECT id FROM users WHERE id = ? FOR UPDATE SKIP LOCKED",
                (row, number) -> row.getLong(1), userId);
        if (owners.isEmpty() && Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM users WHERE id = ?)", Boolean.class, userId))) {
            return false;
        }
        List<Long> states = jdbcTemplate.query("""
                SELECT user_id FROM privacy_notification_states WHERE user_id = ? FOR UPDATE SKIP LOCKED
                """, (row, number) -> row.getLong(1), userId);
        return !states.isEmpty() || !Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM privacy_notification_states WHERE user_id = ?)", Boolean.class, userId));
    }

    private int deleteCandidate(Candidate candidate, Instant now) {
        if ("NOTICE".equals(candidate.kind())) {
            return jdbcTemplate.update("""
                    DELETE FROM privacy_notification_notices WHERE ctid IN (
                        SELECT ctid FROM privacy_notification_notices
                        WHERE user_id = ? AND target_type = ? AND target_id = ?
                          AND (purge_after <= ? OR NOT EXISTS (SELECT 1 FROM users WHERE id = user_id))
                        FOR UPDATE SKIP LOCKED)
                    """, candidate.userId(), candidate.targetType(), candidate.targetId(), Timestamp.from(now));
        }
        if ("RECEIPT".equals(candidate.kind())) {
            return jdbcTemplate.update("""
                    DELETE FROM privacy_notification_receipts WHERE event_id IN (
                        SELECT event_id FROM privacy_notification_receipts WHERE event_id = ?
                          AND (purge_after <= ? OR NOT EXISTS (SELECT 1 FROM users WHERE id = user_id))
                        FOR UPDATE SKIP LOCKED)
                    """, candidate.eventId(), Timestamp.from(now));
        }
        if ("STATE".equals(candidate.kind())) {
            return jdbcTemplate.update("""
                    DELETE FROM privacy_notification_states WHERE user_id = ?
                        AND NOT EXISTS (SELECT 1 FROM users WHERE id = user_id)
                    """, candidate.userId());
        }
        return jdbcTemplate.update("""
                DELETE FROM privacy_notification_events WHERE event_id IN (
                    SELECT event_id FROM privacy_notification_events WHERE event_id = ? AND purge_after <= ?
                    FOR UPDATE SKIP LOCKED)
                """, candidate.eventId(), Timestamp.from(now));
    }

    public Backlog backlog(Instant now) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) AS remaining, min(due_at) AS oldest FROM (
                    SELECT purge_after AS due_at FROM privacy_notification_notices WHERE purge_after <= ?
                        OR NOT EXISTS (SELECT 1 FROM users WHERE id = user_id)
                    UNION ALL
                    SELECT purge_after FROM privacy_notification_receipts WHERE purge_after <= ?
                        OR NOT EXISTS (SELECT 1 FROM users WHERE id = user_id)
                    UNION ALL
                    SELECT NULL::timestamptz FROM privacy_notification_states
                    WHERE NOT EXISTS (SELECT 1 FROM users WHERE id = user_id)
                    UNION ALL
                    SELECT purge_after FROM privacy_notification_events WHERE purge_after <= ?
                ) expired
                """, (row, number) -> {
                    Timestamp oldest = row.getTimestamp("oldest");
                    long delay = 0;
                    if (oldest != null) {
                        delay = Math.max(0, Duration.between(oldest.toInstant(), now).getSeconds());
                    }
                    return new Backlog(row.getLong("remaining"), delay);
                }, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    private record Candidate(String kind, Long userId, UUID eventId, String targetType, String targetId) {
    }

    public record Backlog(long count, long oldestOverdueSeconds) {
    }
}

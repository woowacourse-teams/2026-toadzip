package com.toadzip.backend.interest.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class GuestCancellationRepository {

    private final JdbcTemplate jdbcTemplate;

    public void request(String email, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO notification_guest_cancellation_requests (id, email, requested_at, failed_attempts)
                SELECT ?, ?, ?, 0 WHERE EXISTS (
                    SELECT 1 FROM notification_guest_email_preferences preferences
                    JOIN notification_guest_subscriptions subscriptions
                      ON subscriptions.client_id = preferences.client_id
                    WHERE lower(preferences.email) = ? AND subscriptions.active = true
                      AND subscriptions.expires_at > ?)
                ON CONFLICT (email) DO UPDATE SET requested_at = EXCLUDED.requested_at
                """, UUID.randomUUID(), email, Timestamp.from(now), email, Timestamp.from(now));
    }

    public List<Request> pending() {
        return jdbcTemplate.query("""
                SELECT id, email, requested_at, code_expires_at, failed_attempts, code_sent_at, code_sent_by
                FROM notification_guest_cancellation_requests ORDER BY requested_at ASC
                """, (row, number) -> new Request(
                (UUID) row.getObject("id"), row.getString("email"),
                row.getTimestamp("requested_at").toInstant(),
                row.getTimestamp("code_expires_at") == null ? null : row.getTimestamp("code_expires_at").toInstant(),
                row.getInt("failed_attempts"),
                row.getTimestamp("code_sent_at") == null ? null : row.getTimestamp("code_sent_at").toInstant(),
                row.getString("code_sent_by")));
    }

    public Request findForUpdate(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, email, requested_at, code_expires_at, failed_attempts, code_sent_at, code_sent_by
                FROM notification_guest_cancellation_requests WHERE id = ? FOR UPDATE
                """, (row, number) -> new Request(
                (UUID) row.getObject("id"), row.getString("email"),
                row.getTimestamp("requested_at").toInstant(),
                row.getTimestamp("code_expires_at") == null ? null : row.getTimestamp("code_expires_at").toInstant(),
                row.getInt("failed_attempts"),
                row.getTimestamp("code_sent_at") == null ? null : row.getTimestamp("code_sent_at").toInstant(),
                row.getString("code_sent_by")), id).stream().findFirst().orElse(null);
    }

    public Challenge findChallengeForUpdate(String email) {
        return jdbcTemplate.query("""
                SELECT id, code_hash, code_expires_at, failed_attempts
                FROM notification_guest_cancellation_requests WHERE email = ? FOR UPDATE
                """, (row, number) -> new Challenge(
                (UUID) row.getObject("id"), row.getString("code_hash"),
                row.getTimestamp("code_expires_at") == null ? null : row.getTimestamp("code_expires_at").toInstant(),
                row.getInt("failed_attempts")), email).stream().findFirst().orElse(null);
    }

    public void issue(UUID id, String hash, Instant expiresAt) {
        jdbcTemplate.update("""
                UPDATE notification_guest_cancellation_requests
                SET code_hash = ?, code_expires_at = ?, failed_attempts = 0,
                    code_sent_at = NULL, code_sent_by = NULL WHERE id = ?
                """, hash, Timestamp.from(expiresAt), id);
    }

    public void markSent(UUID id, String sender, Instant sentAt) {
        jdbcTemplate.update("""
                UPDATE notification_guest_cancellation_requests
                SET code_sent_at = ?, code_sent_by = ? WHERE id = ?
                """, Timestamp.from(sentAt), sender, id);
    }

    public void failedAttempt(UUID id) {
        jdbcTemplate.update("""
                UPDATE notification_guest_cancellation_requests
                SET failed_attempts = failed_attempts + 1 WHERE id = ?
                """, id);
    }

    public void cancelGuestSubscriptions(String email) {
        jdbcTemplate.update("""
                DELETE FROM notification_guest_subscriptions subscriptions
                USING notification_guest_email_preferences preferences
                WHERE subscriptions.client_id = preferences.client_id AND lower(preferences.email) = ?
                """, email);
        jdbcTemplate.update("DELETE FROM notification_guest_email_preferences WHERE lower(email) = ?", email);
        jdbcTemplate.update("DELETE FROM notification_guest_cancellation_requests WHERE email = ?", email);
    }

    public void purgeOldRequests(Instant threshold) {
        jdbcTemplate.update("DELETE FROM notification_guest_cancellation_requests WHERE requested_at < ?",
                Timestamp.from(threshold));
    }

    public record Request(UUID id, String email, Instant requestedAt, Instant codeExpiresAt, int failedAttempts,
                          Instant codeSentAt, String codeSentBy) {
    }

    public record Challenge(UUID id, String hash, Instant expiresAt, int failedAttempts) {
    }
}

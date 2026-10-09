package com.toadzip.backend.user.repository;

import com.toadzip.backend.privacy.domain.PrivacyHash;
import com.toadzip.backend.user.exception.SocialAuthorizationRejectedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserLifecycleRepository {

    private final JdbcTemplate jdbcTemplate;

    public void lockIdentifier(String identifier) {
        byte[] digest = HexFormat.of().parseHex(PrivacyHash.sha256(identifier));
        long key = java.nio.ByteBuffer.wrap(digest).getLong();
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(?)", result -> { }, key);
    }

    public Optional<String> identifierOf(long userId) {
        return jdbcTemplate.query("SELECT login_identifier FROM users WHERE id = ?",
                (result, row) -> result.getString(1), userId).stream().findFirst();
    }

    public boolean lockUser(long userId) {
        return !jdbcTemplate.query("SELECT id FROM users WHERE id = ? FOR UPDATE",
                (result, row) -> result.getLong(1), userId).isEmpty();
    }

    public void rejectAuthorizationBeforeDeletion(String identifier, Instant issuedAt, Instant now) {
        Boolean deleted = jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM user_deletion_markers
                WHERE login_identifier_hash = ? AND deleted_at >= ? AND expires_at > ?)
                """, Boolean.class, PrivacyHash.sha256(identifier), Timestamp.from(issuedAt), Timestamp.from(now));
        if (Boolean.TRUE.equals(deleted)) {
            throw new SocialAuthorizationRejectedException();
        }
    }

    public void deletePersonalData(long userId) {
        jdbcTemplate.update("DELETE FROM notification_interest_events WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM notification_subscriptions WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM notification_email_preferences WHERE user_id = ?", userId);
        jdbcTemplate.update("""
                DELETE FROM analytics_consent_events WHERE consent_id IN
                (SELECT id FROM analytics_consents WHERE user_id = ?)
                """, userId);
        jdbcTemplate.update("DELETE FROM analytics_consents WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM user_places WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM user_eligibility_infos WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM favorite_housing_complexes WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM favorite_announcements WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM favorite_regions WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    public void recordDeletion(String identifier, Instant now, Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO user_deletion_markers (login_identifier_hash, deleted_at, expires_at)
                VALUES (?, ?, ?) ON CONFLICT (login_identifier_hash)
                DO UPDATE SET deleted_at = EXCLUDED.deleted_at, expires_at = EXCLUDED.expires_at
                """, PrivacyHash.sha256(identifier), Timestamp.from(now), Timestamp.from(expiresAt));
    }

    public int purgeExpiredMarkers(Instant now) {
        return jdbcTemplate.update("""
                DELETE FROM user_deletion_markers WHERE login_identifier_hash IN
                (SELECT login_identifier_hash FROM user_deletion_markers WHERE expires_at <= ?
                ORDER BY login_identifier_hash LIMIT 500 FOR UPDATE SKIP LOCKED) AND expires_at <= ?
                """, Timestamp.from(now), Timestamp.from(now));
    }

    public OverdueMarkers overdueMarkers(Instant now) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*), COALESCE(EXTRACT(EPOCH FROM (?::timestamptz - min(expires_at))), 0)
                FROM user_deletion_markers WHERE expires_at <= ?
                """, (result, row) -> new OverdueMarkers(result.getLong(1), result.getDouble(2)),
                Timestamp.from(now), Timestamp.from(now));
    }

    public record OverdueMarkers(long count, double oldestSeconds) { }
}

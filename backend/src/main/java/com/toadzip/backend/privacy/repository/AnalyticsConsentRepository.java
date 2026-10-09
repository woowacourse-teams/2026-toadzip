package com.toadzip.backend.privacy.repository;

import com.toadzip.backend.privacy.domain.AnalyticsConsent;
import com.toadzip.backend.privacy.domain.ConsentDecision;
import com.toadzip.backend.privacy.domain.PrivacyHash;
import com.toadzip.backend.privacy.exception.PrivacyException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AnalyticsConsentRepository {

    private final JdbcTemplate jdbcTemplate;

    public void lockMember(long userId) {
        if (jdbcTemplate.query("SELECT id FROM users WHERE id = ? FOR UPDATE",
                (row, number) -> row.getLong(1), userId).isEmpty()) {
            throw new PrivacyException("UNAUTHORIZED", "로그인이 필요합니다.");
        }
    }

    public boolean memberExists(long userId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id = ?)",
                Boolean.class, userId));
    }

    public Optional<AnalyticsConsent> findMember(long userId, boolean lock) {
        return find("user_id", userId, lock);
    }

    public Optional<AnalyticsConsent> findGuest(String token, boolean lock) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            return Optional.empty();
        }
        return find("guest_token_hash", PrivacyHash.sha256(token), lock);
    }

    private Optional<AnalyticsConsent> find(String field, Object value, boolean lock) {
        String suffix = "";
        if (lock) {
            suffix = " FOR UPDATE";
        }
        return jdbcTemplate.query("SELECT * FROM privacy_analytics_consents WHERE " + field + " = ?" + suffix,
                (row, number) -> map(row), value).stream().findFirst();
    }

    public AnalyticsConsent createMember(long userId, Instant now) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO privacy_analytics_consents (id, user_id, decision, revision, created_at, updated_at)
                VALUES (?, ?, 'UNSET', 0, ?, ?)
                """, id, userId, Timestamp.from(now), Timestamp.from(now));
        return findMember(userId, true).orElseThrow();
    }

    public AnalyticsConsent createGuest(String token, Instant now, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO privacy_analytics_consents
                    (id, guest_token_hash, decision, revision, created_at, updated_at, expires_at)
                VALUES (?, ?, 'UNSET', 0, ?, ?, ?)
                """, id, PrivacyHash.sha256(token), Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(expiresAt));
        return findGuest(token, true).orElseThrow();
    }

    public void save(AnalyticsConsent consent) {
        jdbcTemplate.update("""
                UPDATE privacy_analytics_consents SET decision = ?, notice_version = ?, scope_version = ?,
                    decided_at = ?, expires_at = ?, revision = ?, updated_at = ? WHERE id = ?
                """, consent.getDecision().name(), consent.getNoticeVersion(), consent.getScopeVersion(),
                timestamp(consent.getDecidedAt()), timestamp(consent.getExpiresAt()), consent.getRevision(),
                Timestamp.from(consent.getUpdatedAt()), consent.getId());
    }

    /** Caller owns the transaction and must store the analytics event before releasing these locks. */
    public AnalyticsConsent lockForCollection(Long userId, String guestToken) {
        if (userId != null) {
            lockMember(userId);
            return findMember(userId, true).orElse(null);
        }
        return findGuest(guestToken, true).orElse(null);
    }

    static AnalyticsConsent map(ResultSet row) throws SQLException {
        return AnalyticsConsent.restore(row.getObject("id", UUID.class), row.getString("guest_token_hash"),
                ConsentDecision.valueOf(row.getString("decision")), row.getString("notice_version"),
                row.getString("scope_version"), instant(row, "decided_at"), instant(row, "expires_at"),
                row.getLong("revision"), instant(row, "created_at"), instant(row, "updated_at"));
    }

    static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp timestamp = row.getTimestamp(column);
        if (timestamp == null) {
            return null;
        }
        return timestamp.toInstant();
    }

    static Timestamp timestamp(Instant instant) {
        if (instant == null) {
            return null;
        }
        return Timestamp.from(instant);
    }
}

package com.toadzip.backend.privacy.repository;

import com.toadzip.backend.privacy.domain.AnalyticsConsent;
import com.toadzip.backend.privacy.domain.ConsentDecision;
import com.toadzip.backend.privacy.domain.PrivacyRetentionPolicy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PrivacyRetentionRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<Candidate> candidates(Instant now, String scope, Map<String, Instant> deadlines,
            Instant fallbackDeadline, int limit) {
        List<Object> arguments = new ArrayList<>();
        arguments.add(Timestamp.from(now));
        arguments.add(Timestamp.from(now.minus(PrivacyRetentionPolicy.SUPERSEDED_CONSENT_HISTORY_RETENTION)));
        arguments.add(Timestamp.from(now));
        arguments.add(scope);
        String deadlineCase = deadlineCase(deadlines, fallbackDeadline, arguments);
        arguments.add(limit);
        return jdbcTemplate.query("""
                SELECT id, user_id FROM privacy_analytics_consents c WHERE
                    (guest_token_hash IS NOT NULL AND
                        ((decision = 'UNSET' AND expires_at <= ?) OR expires_at <= ?))
                    OR (user_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM users WHERE users.id = c.user_id))
                    OR EXISTS (SELECT 1 FROM privacy_analytics_consent_events e WHERE e.consent_id = c.id AND
                        (e.purge_after <= ? OR (e.decision = 'GRANTED' AND e.scope_version <> ?
                            AND (e.purge_after IS NULL OR e.purge_after > %s))))
                ORDER BY id LIMIT ?
                """.formatted(deadlineCase), (row, number) -> new Candidate(row.getObject("id", UUID.class),
                row.getObject("user_id", Long.class)), arguments.toArray());
    }

    private String deadlineCase(Map<String, Instant> deadlines, Instant fallback, List<Object> arguments) {
        StringBuilder expression = new StringBuilder("CASE ");
        deadlines.forEach((scope, deadline) -> {
            expression.append("WHEN e.scope_version = ? THEN CAST(? AS TIMESTAMPTZ) ");
            arguments.add(scope);
            arguments.add(Timestamp.from(deadline));
        });
        expression.append("ELSE CAST(? AS TIMESTAMPTZ) END");
        arguments.add(Timestamp.from(fallback));
        if (deadlines.isEmpty()) {
            return "CAST(? AS TIMESTAMPTZ)";
        }
        return expression.toString();
    }

    public AnalyticsConsent lockCandidate(Candidate candidate) {
        if (candidate.userId() != null && jdbcTemplate.query(
                "SELECT id FROM users WHERE id = ? FOR UPDATE SKIP LOCKED", (row, number) -> row.getLong(1),
                candidate.userId()).isEmpty() && memberExists(candidate.userId())) {
            return null;
        }
        return jdbcTemplate.query("SELECT * FROM privacy_analytics_consents WHERE id = ? FOR UPDATE SKIP LOCKED",
                (row, number) -> AnalyticsConsentRepository.map(row), candidate.id()).stream().findFirst().orElse(null);
    }

    private boolean memberExists(long userId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM users WHERE id = ?)", Boolean.class, userId));
    }

    public boolean isOrphan(Candidate candidate) {
        return candidate.userId() != null && !memberExists(candidate.userId());
    }

    public int deleteOrphan(Candidate candidate, int limit) {
        int deleted = jdbcTemplate.update("""
                DELETE FROM privacy_analytics_consent_events WHERE id IN (
                    SELECT id FROM privacy_analytics_consent_events WHERE consent_id = ? ORDER BY id LIMIT ?)
                """, candidate.id(), limit);
        if (deleted < limit) {
            deleted += jdbcTemplate.update("""
                    DELETE FROM privacy_analytics_consents c WHERE id = ? AND user_id IS NOT NULL
                        AND NOT EXISTS (SELECT 1 FROM users WHERE users.id = c.user_id)
                        AND NOT EXISTS (SELECT 1 FROM privacy_analytics_consent_events WHERE consent_id = c.id)
                    """, candidate.id());
        }
        return deleted;
    }

    public int advanceScopePurge(UUID consentId, String scope, Map<String, Instant> deadlines,
            Instant fallback, int limit) {
        List<Object> arguments = new ArrayList<>();
        String firstCase = deadlineCase(deadlines, fallback, arguments);
        arguments.add(consentId);
        arguments.add(scope);
        String secondCase = deadlineCase(deadlines, fallback, arguments);
        arguments.add(limit);
        return jdbcTemplate.update("""
                UPDATE privacy_analytics_consent_events e SET purge_after = LEAST(purge_after, %s)
                WHERE id IN (SELECT id FROM privacy_analytics_consent_events e WHERE consent_id = ?
                    AND decision = 'GRANTED' AND scope_version <> ?
                    AND (purge_after IS NULL OR purge_after > %s) ORDER BY id LIMIT ?)
                """.formatted(firstCase, secondCase), arguments.toArray());
    }

    public int deleteExpiredEvents(UUID consentId, Instant now, int limit) {
        return jdbcTemplate.update("""
                DELETE FROM privacy_analytics_consent_events WHERE id IN
                    (SELECT id FROM privacy_analytics_consent_events WHERE consent_id = ? AND purge_after <= ?
                    ORDER BY id LIMIT ?)
                """, consentId, Timestamp.from(now), limit);
    }

    public boolean guestCanBePurged(AnalyticsConsent consent, Instant now) {
        if (!consent.isGuest()) {
            return false;
        }
        Instant purgeAfter = consent.getExpiresAt().plus(PrivacyRetentionPolicy.SUPERSEDED_CONSENT_HISTORY_RETENTION);
        if (consent.getDecision() == ConsentDecision.UNSET) {
            purgeAfter = consent.getExpiresAt();
        }
        return !purgeAfter.isAfter(now);
    }

    public int deleteGuest(AnalyticsConsent consent) {
        return jdbcTemplate.update("""
                DELETE FROM privacy_analytics_consents WHERE id = ? AND guest_token_hash IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM privacy_analytics_consent_events WHERE consent_id = ?)
                """, consent.getId(), consent.getId());
    }

    public Overdue overdue(Instant now, String scope, Map<String, Instant> deadlines, Instant fallbackDeadline) {
        List<Object> arguments = new ArrayList<>();
        arguments.add(Timestamp.from(now));
        arguments.add(scope);
        String deadlineCase = deadlineCase(deadlines, fallbackDeadline, arguments);
        arguments.add(PrivacyRetentionPolicy.SUPERSEDED_CONSENT_HISTORY_RETENTION.toSeconds() + " seconds");
        arguments.add(Timestamp.from(now));
        arguments.add(Timestamp.from(now));
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) AS count, COALESCE(EXTRACT(EPOCH FROM (? - MIN(purge_after))), 0) AS delay
                FROM (
                    SELECT CASE WHEN e.decision = 'GRANTED' AND e.scope_version <> ?
                        THEN LEAST(e.purge_after, %s) ELSE e.purge_after END AS purge_after
                    FROM privacy_analytics_consent_events e
                    UNION ALL
                    SELECT CASE WHEN decision = 'UNSET' THEN expires_at
                        ELSE expires_at + CAST(? AS INTERVAL) END AS purge_after
                    FROM privacy_analytics_consents WHERE guest_token_hash IS NOT NULL
                    UNION ALL
                    SELECT CAST(? AS TIMESTAMPTZ) AS purge_after FROM privacy_analytics_consents c
                    WHERE user_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM users WHERE users.id = c.user_id)
                ) candidates WHERE purge_after <= ?
                """.formatted(deadlineCase),
                (row, number) -> new Overdue(row.getLong("count"), Math.max(0, row.getDouble("delay"))),
                arguments.toArray());
    }

    public record Candidate(UUID id, Long userId) {
    }

    public record Overdue(long count, double oldestSeconds) {
    }
}

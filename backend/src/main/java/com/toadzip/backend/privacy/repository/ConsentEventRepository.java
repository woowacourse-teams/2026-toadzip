package com.toadzip.backend.privacy.repository;

import com.toadzip.backend.privacy.domain.AnalyticsConsent;
import com.toadzip.backend.privacy.domain.ConsentCommand;
import com.toadzip.backend.privacy.domain.ConsentDecision;
import com.toadzip.backend.privacy.domain.ConsentReceipt;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ConsentEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public Optional<ConsentReceipt> find(UUID consentId, UUID commandId) {
        return jdbcTemplate.query("""
                SELECT command_id, request_fingerprint, decision, revision, recorded_at
                FROM privacy_analytics_consent_events WHERE consent_id = ? AND command_id = ?
                """, (row, number) -> new ConsentReceipt(row.getObject("command_id", UUID.class),
                row.getString("request_fingerprint"), ConsentDecision.valueOf(row.getString("decision")),
                row.getLong("revision"), row.getTimestamp("recorded_at").toInstant()), consentId, commandId)
                .stream().findFirst();
    }

    public void supersede(UUID consentId, long revision, Instant now, Instant purgeAfter) {
        jdbcTemplate.update("""
                UPDATE privacy_analytics_consent_events SET superseded_at = ?, purge_after = LEAST(purge_after, ?)
                WHERE consent_id = ? AND revision = ?
                """, Timestamp.from(now), Timestamp.from(purgeAfter), consentId, revision);
    }

    public void record(AnalyticsConsent previous, AnalyticsConsent current, ConsentCommand command,
            Instant purgeAfter) {
        jdbcTemplate.update("""
                INSERT INTO privacy_analytics_consent_events
                    (id, consent_id, command_id, request_fingerprint, previous_decision, decision,
                    previous_revision, revision, previous_notice_version, previous_scope_version, notice_version,
                    scope_version, source, recorded_at, expires_at, purge_after)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), current.getId(), command.commandId(), command.fingerprint(),
                previous.getDecision().name(), current.getDecision().name(), previous.getRevision(),
                current.getRevision(), previous.getNoticeVersion(), previous.getScopeVersion(),
                current.getNoticeVersion(), current.getScopeVersion(), command.source().name(),
                Timestamp.from(current.getDecidedAt()), AnalyticsConsentRepository.timestamp(current.getExpiresAt()),
                AnalyticsConsentRepository.timestamp(purgeAfter));
    }
}

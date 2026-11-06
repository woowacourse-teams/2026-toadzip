package com.toadzip.backend.basiccomparison.repository;

import com.toadzip.backend.basiccomparison.domain.EligibilityBasis;
import com.toadzip.backend.basiccomparison.domain.EligibilityDecision;
import com.toadzip.backend.basiccomparison.domain.StoredEligibilityResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EligibilityResultRepository {
    private final JdbcClient jdbc;
    private final ComparisonJsonCodec codec;

    public EligibilityResultRepository(JdbcClient jdbc, ComparisonJsonCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    public Optional<StoredEligibilityResult> find(long userId, long rootId) {
        return jdbc.sql("SELECT * FROM comparison_latest_results WHERE user_id = ? AND announcement_id = ?")
                .params(userId, rootId).query(this::result).optional();
    }

    public void save(StoredEligibilityResult stored) {
        EligibilityBasis basis = stored.basis();
        String status = "NOT_EVALUATED";
        String encoded = null;
        if (stored.result() != null) {
            status = stored.result().status().name();
            encoded = codec.write(stored.result());
        }
        jdbc.sql("""
                        INSERT INTO comparison_latest_results(user_id, announcement_id, resolved_announcement_id,
                            profile_version, notice_generation, rule_set_id, availability, application_status,
                            evaluator_version, status, result, evaluated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                        ON CONFLICT(user_id, announcement_id) DO UPDATE SET
                            resolved_announcement_id = EXCLUDED.resolved_announcement_id,
                            profile_version = EXCLUDED.profile_version, notice_generation = EXCLUDED.notice_generation,
                            rule_set_id = EXCLUDED.rule_set_id, availability = EXCLUDED.availability,
                            application_status = EXCLUDED.application_status,
                            evaluator_version = EXCLUDED.evaluator_version,
                            status = EXCLUDED.status, result = EXCLUDED.result, evaluated_at = EXCLUDED.evaluated_at
                        """).params(basis.userId(), basis.rootAnnouncementId(), basis.announcementId(),
                        basis.profileVersion(), basis.noticeGeneration(), basis.ruleSetId(), basis.availability(),
                        basis.applicationStatus(), basis.evaluatorVersion(), status, encoded,
                        stored.evaluatedAt().atOffset(ZoneOffset.UTC)).update();
    }

    public List<Long> resultUsersAfter(long rootId, long cursor, int limit) {
        return jdbc.sql("SELECT user_id FROM comparison_latest_results WHERE announcement_id = ? AND user_id > ?"
                        + " ORDER BY user_id LIMIT ?").params(rootId, cursor, limit).query(Long.class).list();
    }

    public void delete(long userId, long rootId) {
        jdbc.sql("DELETE FROM comparison_latest_results WHERE user_id = ? AND announcement_id = ?")
                .params(userId, rootId).update();
    }

    public Optional<Discovery> discovery(long userId, long rootId) {
        return jdbc.sql("SELECT * FROM comparison_discoveries WHERE user_id = ? AND announcement_id = ?")
                .params(userId, rootId).query(this::discovery).optional();
    }

    public List<Discovery> discoveriesAfter(long userId, long cursor, int limit) {
        return jdbc.sql("SELECT d.* FROM comparison_discoveries d JOIN comparison_notice_rechecks n"
                        + " ON n.announcement_id = d.announcement_id WHERE d.user_id = ? AND d.announcement_id > ?"
                        + " AND d.acknowledged_at IS NULL AND NOT n.retired ORDER BY d.announcement_id LIMIT ?")
                .params(userId, cursor, limit).query(this::discovery).list();
    }

    public void newDiscovery(EligibilityBasis basis, List<String> options, Instant now) {
        jdbc.sql("""
                        INSERT INTO comparison_discoveries(user_id, announcement_id, profile_version,
                            notice_generation, option_ids, discovered_at)
                        VALUES (?, ?, ?, ?, ?::jsonb, ?)
                        ON CONFLICT(user_id, announcement_id) DO UPDATE SET event_id = gen_random_uuid(),
                            profile_version = EXCLUDED.profile_version, notice_generation = EXCLUDED.notice_generation,
                            option_ids = EXCLUDED.option_ids, discovered_at = EXCLUDED.discovered_at,
                            acknowledged_at = NULL
                        """).params(basis.userId(), basis.rootAnnouncementId(), basis.profileVersion(),
                        basis.noticeGeneration(), codec.write(options), now.atOffset(ZoneOffset.UTC)).update();
    }

    public void refreshDiscovery(EligibilityBasis basis, List<String> options) {
        if (options.isEmpty()) {
            jdbc.sql("DELETE FROM comparison_discoveries WHERE user_id = ? AND announcement_id = ?")
                    .params(basis.userId(), basis.rootAnnouncementId()).update();
            return;
        }
        jdbc.sql("UPDATE comparison_discoveries SET profile_version = ?, notice_generation = ?, option_ids = ?::jsonb"
                        + " WHERE user_id = ? AND announcement_id = ?")
                .params(basis.profileVersion(), basis.noticeGeneration(), codec.write(options),
                        basis.userId(), basis.rootAnnouncementId()).update();
    }

    public boolean acknowledge(long userId, long rootId, UUID eventId, Instant now) {
        return jdbc.sql("UPDATE comparison_discoveries SET acknowledged_at = COALESCE(acknowledged_at, ?)"
                        + " WHERE user_id = ? AND announcement_id = ? AND event_id = ?")
                .params(now.atOffset(ZoneOffset.UTC), userId, rootId, eventId).update() == 1;
    }

    private StoredEligibilityResult result(ResultSet row, int index) throws SQLException {
        EligibilityBasis basis = new EligibilityBasis(row.getLong("user_id"), row.getLong("announcement_id"),
                row.getLong("resolved_announcement_id"), row.getLong("profile_version"),
                row.getLong("notice_generation"), row.getObject("rule_set_id", Long.class),
                row.getString("availability"), row.getString("application_status"),
                row.getString("evaluator_version"));
        EligibilityDecision decision = null;
        String encoded = row.getString("result");
        if (encoded != null) {
            decision = codec.decision(encoded);
        }
        return new StoredEligibilityResult(basis, decision,
                row.getObject("evaluated_at", OffsetDateTime.class).toInstant());
    }

    private Discovery discovery(ResultSet row, int index) throws SQLException {
        Instant acknowledgedAt = null;
        OffsetDateTime value = row.getObject("acknowledged_at", OffsetDateTime.class);
        if (value != null) {
            acknowledgedAt = value.toInstant();
        }
        return new Discovery(row.getLong("announcement_id"), row.getObject("event_id", UUID.class),
                row.getLong("profile_version"), row.getLong("notice_generation"),
                codec.strings(row.getString("option_ids")),
                row.getObject("discovered_at", OffsetDateTime.class).toInstant(), acknowledgedAt);
    }

    public record Discovery(long announcementId, UUID eventId, long profileVersion, long noticeGeneration,
            List<String> optionIds, Instant discoveredAt, Instant acknowledgedAt) {}
}

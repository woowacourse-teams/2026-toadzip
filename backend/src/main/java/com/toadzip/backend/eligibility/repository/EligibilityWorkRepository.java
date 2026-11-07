package com.toadzip.backend.eligibility.repository;

import com.toadzip.backend.eligibility.domain.EligibilityWork.Kind;
import com.toadzip.backend.eligibility.domain.EligibilityWork;
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
public class EligibilityWorkRepository {
    private final JdbcClient jdbc;

    public EligibilityWorkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EligibilityWork> claim(Kind kind, Instant now, int leaseSeconds) {
        Names names = names(kind);
        String retired = "false";
        if (kind == Kind.NOTICE) {
            retired = "retired";
        }
        return jdbc.sql("""
                        UPDATE %1$s SET state = 'RUNNING', claim_token = gen_random_uuid(),
                            lease_until = :now + make_interval(secs => :lease), updated_at = :now
                        WHERE %2$s = (SELECT %2$s FROM %1$s
                            WHERE (state = 'PENDING' AND next_attempt_at <= :now)
                               OR (state = 'RUNNING' AND lease_until <= :now)
                            ORDER BY next_attempt_at, %2$s LIMIT 1 FOR UPDATE SKIP LOCKED)
                        RETURNING %2$s AS id, %3$s AS revision, %4$s AS cursor,
                            claim_token, lease_until, %5$s AS retired
                        """.formatted(names.table(), names.id(), names.revision(), names.cursor(), retired))
                .param("now", time(now)).param("lease", leaseSeconds)
                .query((row, index) -> work(row, kind)).optional();
    }

    public boolean lockUser(long userId) {
        return jdbc.sql("SELECT id FROM users WHERE id = ? FOR UPDATE").param(userId)
                .query(Long.class).optional().isPresent();
    }

    public boolean lockClaim(EligibilityWork work, Instant now) {
        Names names = names(work.kind());
        return jdbc.sql("""
                        SELECT %2$s FROM %1$s WHERE %2$s = :id AND %3$s = :revision
                            AND state = 'RUNNING' AND claim_token = :token AND lease_until > :now FOR UPDATE
                        """.formatted(names.table(), names.id(), names.revision()))
                .param("id", work.id()).param("revision", work.revision()).param("token", work.token())
                .param("now", time(now)).query(Long.class).optional().isPresent();
    }

    public Optional<NoticeState> notice(long rootId, boolean lock) {
        String sql = "SELECT generation, retired, observed_basis FROM comparison_notice_rechecks"
                + " WHERE announcement_id = ?";
        if (lock) {
            sql += " FOR UPDATE";
        }
        return jdbc.sql(sql).param(rootId).query((row, index) -> new NoticeState(
                row.getLong("generation"), row.getBoolean("retired"), row.getString("observed_basis"))).optional();
    }

    public boolean checkpoint(EligibilityWork work, long cursor, Instant now) {
        Names names = names(work.kind());
        return jdbc.sql("""
                        UPDATE %1$s SET %4$s = greatest(%4$s, :cursor), updated_at = :now
                        WHERE %2$s = :id AND %3$s = :revision AND claim_token = :token
                            AND state = 'RUNNING' AND lease_until > :now
                        """.formatted(names.table(), names.id(), names.revision(), names.cursor()))
                .param("cursor", cursor).param("now", time(now)).param("id", work.id())
                .param("revision", work.revision()).param("token", work.token()).update() == 1;
    }

    public void progress(EligibilityWork work, long cursor, boolean completed, Instant now) {
        Names names = names(work.kind());
        String state = "PENDING";
        if (completed) {
            state = "DONE";
        }
        jdbc.sql("""
                        UPDATE %1$s SET %4$s = greatest(%4$s, :cursor), state = :state,
                            claim_token = NULL, lease_until = NULL,
                            next_attempt_at = :now, attempts = 0, last_error_code = NULL, updated_at = :now
                        WHERE %2$s = :id AND %3$s = :revision AND claim_token = :token
                            AND state = 'RUNNING' AND lease_until > :now
                        """.formatted(names.table(), names.id(), names.revision(), names.cursor()))
                .param("cursor", cursor).param("state", state).param("now", time(now))
                .param("id", work.id()).param("revision", work.revision()).param("token", work.token()).update();
    }

    public void retry(EligibilityWork work, Instant now, String errorCode) {
        Names names = names(work.kind());
        jdbc.sql("""
                        UPDATE %1$s SET state = 'PENDING', claim_token = NULL, lease_until = NULL,
                            attempts = least(attempts + 1, 1000), last_error_code = :error,
                            next_attempt_at = :now
                                + make_interval(secs => least(300, power(2, least(attempts, 8))::int)),
                            updated_at = :now
                        WHERE %2$s = :id AND %3$s = :revision AND claim_token = :token
                        """.formatted(names.table(), names.id(), names.revision()))
                .param("error", errorCode).param("now", time(now)).param("id", work.id())
                .param("revision", work.revision()).param("token", work.token()).update();
    }

    public List<Long> usersAfter(long cursor, int limit) {
        return jdbc.sql("SELECT id FROM users WHERE id > ? ORDER BY id LIMIT ?")
                .params(cursor, limit).query(Long.class).list();
    }

    public List<Long> rootsAfter(long cursor, int limit) {
        return jdbc.sql("SELECT announcement_id FROM comparison_notice_rechecks WHERE announcement_id > ?"
                        + " AND NOT retired ORDER BY announcement_id LIMIT ?")
                .params(cursor, limit).query(Long.class).list();
    }

    /** Reconciliation never resets a same-basis cursor or claim. */
    public void observe(long rootId, long generation, String key) {
        jdbc.sql("""
                        UPDATE comparison_notice_rechecks SET observed_basis = :basis
                        WHERE announcement_id = :id AND generation = :generation AND observed_basis IS NULL
                        """).param("basis", key).param("id", rootId).param("generation", generation).update();
        jdbc.sql("""
                        UPDATE comparison_notice_rechecks SET observed_basis = :basis, generation = generation + 1,
                            cursor_user_id = 0, state = 'PENDING', claim_token = NULL, lease_until = NULL,
                            next_attempt_at = '1970-01-01 UTC', attempts = 0, last_error_code = NULL, updated_at = now()
                        WHERE announcement_id = :id AND generation = :generation AND observed_basis <> :basis
                        """).param("basis", key).param("id", rootId).param("generation", generation).update();
    }

    public ProfileProgress profileProgress(long userId) {
        return jdbc.sql("SELECT requested_version, state, cursor_announcement_id, attempts"
                        + " FROM comparison_profile_rechecks"
                        + " WHERE user_id = ?")
                .param(userId).query((row, index) -> new ProfileProgress(row.getLong("requested_version"),
                        row.getString("state"), row.getLong("cursor_announcement_id"), row.getInt("attempts")))
                .optional().orElse(new ProfileProgress(0, "PENDING", 0, 0));
    }

    private EligibilityWork work(ResultSet row, Kind kind) throws SQLException {
        return new EligibilityWork(kind, row.getLong("id"), row.getLong("revision"), row.getLong("cursor"),
                row.getObject("claim_token", UUID.class),
                row.getObject("lease_until", OffsetDateTime.class).toInstant(),
                row.getBoolean("retired"));
    }

    private Names names(Kind kind) {
        if (kind == Kind.PROFILE) {
            return new Names("comparison_profile_rechecks", "user_id", "requested_version", "cursor_announcement_id");
        }
        return new Names("comparison_notice_rechecks", "announcement_id", "generation", "cursor_user_id");
    }

    private OffsetDateTime time(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record Names(String table, String id, String revision, String cursor) {}
    public record NoticeState(long generation, boolean retired, String observedBasis) {}
    public record ProfileProgress(long requestedVersion, String state, long cursorAnnouncementId, int failedAttempts) {}
}

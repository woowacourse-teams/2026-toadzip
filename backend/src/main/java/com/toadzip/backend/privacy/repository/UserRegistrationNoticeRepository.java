package com.toadzip.backend.privacy.repository;

import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserRegistrationNoticeRepository {

    private final JdbcTemplate jdbcTemplate;

    public void record(long userId, String policyVersion, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO privacy_registration_notices (user_id, policy_version, recorded_at)
                VALUES (?, ?, ?) ON CONFLICT (user_id) DO NOTHING
                """, userId, policyVersion, Timestamp.from(now));
    }

    public int markOrphans(Instant now, int limit) {
        return jdbcTemplate.update("""
                UPDATE privacy_registration_notices SET purge_after = ? WHERE user_id IN (
                    SELECT notice.user_id FROM privacy_registration_notices notice
                    WHERE notice.purge_after IS NULL
                        AND NOT EXISTS (SELECT 1 FROM users WHERE id = notice.user_id)
                    ORDER BY notice.user_id LIMIT ? FOR UPDATE SKIP LOCKED)
                """, Timestamp.from(now), limit);
    }

    public int purgeOrphans(Instant now, int limit) {
        return jdbcTemplate.update("""
                DELETE FROM privacy_registration_notices WHERE user_id IN (
                    SELECT notice.user_id FROM privacy_registration_notices notice
                    WHERE notice.purge_after <= ?
                        AND NOT EXISTS (SELECT 1 FROM users WHERE id = notice.user_id)
                    ORDER BY notice.user_id LIMIT ? FOR UPDATE SKIP LOCKED)
                """, Timestamp.from(now), limit);
    }

    public Overdue overdue(Instant now) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*), COALESCE(EXTRACT(EPOCH FROM (?::timestamptz - min(notice.purge_after))), 0)
                FROM privacy_registration_notices notice
                WHERE NOT EXISTS (SELECT 1 FROM users WHERE id = notice.user_id)
                    AND (notice.purge_after IS NULL OR notice.purge_after <= ?)
                """, (result, row) -> new Overdue(result.getLong(1), result.getDouble(2)),
                Timestamp.from(now), Timestamp.from(now));
    }

    public record Overdue(long count, double oldestSeconds) {
    }
}

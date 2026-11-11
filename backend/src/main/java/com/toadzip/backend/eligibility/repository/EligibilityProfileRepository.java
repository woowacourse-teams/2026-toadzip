package com.toadzip.backend.eligibility.repository;

import com.toadzip.backend.eligibility.domain.StoredEligibilityProfile;
import com.toadzip.backend.eligibility.exception.EligibilityConflictException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Repository
public class EligibilityProfileRepository {
    private final JdbcClient jdbc;
    private final EligibilityJsonCodec codec;

    public EligibilityProfileRepository(JdbcClient jdbc, EligibilityJsonCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    public StoredEligibilityProfile find(long userId) {
        return jdbc.sql("SELECT * FROM basic_comparison_profiles WHERE user_id = ?")
                .param(userId)
                .query(this::map)
                .optional()
                .orElseGet(StoredEligibilityProfile::empty);
    }

    public long version(long userId) {
        return jdbc.sql("SELECT version FROM basic_comparison_profiles WHERE user_id = ?")
                .param(userId).query(Long.class).optional().orElse(0L);
    }

    public void lockUser(long userId) {
        jdbc.sql("SELECT id FROM users WHERE id = ? FOR UPDATE").param(userId).query(Long.class).single();
    }

    public StoredEligibilityProfile save(
            long userId, long expectedVersion, String encoded, Instant now) {
        if (expectedVersion == 0) {
            return jdbc.sql(
                            """
                            INSERT INTO basic_comparison_profiles (user_id, version, profile, updated_at)
                            VALUES (?, 1, ?::jsonb, ?) ON CONFLICT (user_id) DO NOTHING RETURNING *
                            """)
                    .params(userId, encoded, now.atOffset(ZoneOffset.UTC))
                    .query(this::map)
                    .optional()
                    .orElseThrow(EligibilityConflictException::new);
        }
        return jdbc.sql(
                        """
                        UPDATE basic_comparison_profiles SET input_contract_version = 3, version = version + 1, profile = ?::jsonb, updated_at = ?
                        WHERE user_id = ? AND version = ? RETURNING *
                        """)
                .params(encoded, now.atOffset(ZoneOffset.UTC), userId, expectedVersion)
                .query(this::map)
                .optional()
                .orElseThrow(EligibilityConflictException::new);
    }

    private StoredEligibilityProfile map(ResultSet row, int index) throws SQLException {
        return new StoredEligibilityProfile(
                row.getLong("version"),
                row.getObject("updated_at", OffsetDateTime.class).toInstant(),
                codec.profile(row.getString("profile"), row.getInt("input_contract_version")));
    }
}

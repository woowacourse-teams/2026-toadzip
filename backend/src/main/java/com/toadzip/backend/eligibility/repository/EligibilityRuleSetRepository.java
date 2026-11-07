package com.toadzip.backend.eligibility.repository;

import com.toadzip.backend.eligibility.domain.StoredEligibilityRuleSet;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class EligibilityRuleSetRepository {
    private final JdbcClient jdbc;

    public EligibilityRuleSetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredEligibilityRuleSet> find(long announcementId, String status) {
        return jdbc.sql(
                        "SELECT * FROM basic_comparison_rule_sets WHERE announcement_id = ? AND"
                                + " status = ?")
                .params(announcementId, status)
                .query(this::map)
                .optional();
    }

    public Optional<StoredEligibilityRuleSet> lockDraft(long announcementId) {
        return jdbc.sql(
                        """
                        SELECT * FROM basic_comparison_rule_sets
                        WHERE announcement_id = ? AND status = 'DRAFT' FOR UPDATE
                        """)
                .param(announcementId)
                .query(this::map)
                .optional();
    }

    public StoredEligibilityRuleSet insertDraft(long announcementId, String content, Instant now) {
        return jdbc.sql(
                        """
                        INSERT INTO basic_comparison_rule_sets (announcement_id, content, updated_at)
                        VALUES (?, ?::jsonb, ?) RETURNING *
                        """)
                .params(announcementId, content, now.atOffset(ZoneOffset.UTC))
                .query(this::map)
                .single();
    }

    public StoredEligibilityRuleSet updateDraft(long id, String content, Instant now) {
        return jdbc.sql(
                        """
                        UPDATE basic_comparison_rule_sets
                        SET content = ?::jsonb, schema_version = 3, revision = revision + 1, updated_at = ?
                        WHERE id = ? AND status = 'DRAFT' RETURNING *
                        """)
                .params(content, now.atOffset(ZoneOffset.UTC), id)
                .query(this::map)
                .single();
    }

    public long currentVersion(long announcementId) {
        return jdbc.sql(
                        """
                        SELECT COALESCE(MAX(version), 0) FROM basic_comparison_rule_sets
                        WHERE announcement_id = ? AND status = 'PUBLISHED'
                        """)
                .param(announcementId)
                .query(Long.class)
                .single();
    }

    public void deletePublished(List<Long> announcementIds) {
        jdbc.sql(
                        "DELETE FROM basic_comparison_rule_sets WHERE announcement_id IN (:ids) AND"
                                + " status = 'PUBLISHED'")
                .param("ids", announcementIds)
                .update();
    }

    public StoredEligibilityRuleSet publish(long id, long version, Instant now) {
        return jdbc.sql(
                        """
                        UPDATE basic_comparison_rule_sets
                        SET status = 'PUBLISHED', version = ?, published_at = ?, updated_at = ?
                        WHERE id = ? AND status = 'DRAFT' RETURNING *
                        """)
                .params(version, now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC), id)
                .query(this::map)
                .single();
    }

    private StoredEligibilityRuleSet map(ResultSet row, int index) throws SQLException {
        Instant publishedAt = null;
        if (row.getObject("published_at") != null) {
            publishedAt = row.getObject("published_at", OffsetDateTime.class).toInstant();
        }
        return new StoredEligibilityRuleSet(
                row.getLong("id"),
                row.getLong("announcement_id"),
                row.getString("status"),
                row.getLong("revision"),
                row.getLong("version"),
                row.getInt("schema_version"),
                row.getString("content"),
                row.getObject("updated_at", OffsetDateTime.class).toInstant(),
                publishedAt);
    }
}

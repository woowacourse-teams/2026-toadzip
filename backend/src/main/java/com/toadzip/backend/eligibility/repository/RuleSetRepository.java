package com.toadzip.backend.eligibility.repository;

import com.toadzip.backend.eligibility.domain.RuleSetMetadata;
import com.toadzip.backend.eligibility.domain.StoredRuleSet;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class RuleSetRepository {
    private final JdbcClient jdbc;

    public RuleSetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredRuleSet> find(long announcementId, String status) {
        return jdbc.sql(
                        "SELECT * FROM announcement_rule_sets WHERE announcement_id = ? AND status"
                            + " = ?")
                .params(announcementId, status)
                .query(this::map)
                .optional();
    }

    public Optional<StoredRuleSet> lockDraft(long announcementId) {
        return jdbc.sql(
                        "SELECT * FROM announcement_rule_sets WHERE announcement_id = ? AND status"
                            + " = 'DRAFT' FOR UPDATE")
                .param(announcementId)
                .query(this::map)
                .optional();
    }

    public StoredRuleSet insertDraft(long announcementId, RuleSetMetadata metadata, Instant now) {
        Map<String, Object> values = metadata(metadata, now);
        values.put("announcementId", announcementId);
        return jdbc.sql(
                        """
                        INSERT INTO announcement_rule_sets (announcement_id, status, recruitment_kind, applicant_kind,
                            has_eligibility_relaxation, source_url, source_published_at, collected_at,
                            eligibility_reference_date, structuring_status, created_at, updated_at)
                        VALUES (:announcementId, 'DRAFT', :recruitmentKind, :applicantKind, :relaxation,
                            :sourceUrl, :sourcePublishedAt, :collectedAt, :referenceDate, :structuringStatus, :now,
                            :now)
                        RETURNING *
                        """)
                .params(values)
                .query(this::map)
                .single();
    }

    public StoredRuleSet updateDraft(long id, RuleSetMetadata metadata, Instant now) {
        Map<String, Object> values = metadata(metadata, now);
        values.put("id", id);
        return jdbc.sql(
                        """
                        UPDATE announcement_rule_sets SET recruitment_kind = :recruitmentKind, applicant_kind =
                        :applicantKind,
                            has_eligibility_relaxation = :relaxation, source_url = :sourceUrl,
                            source_published_at = :sourcePublishedAt, collected_at = :collectedAt,
                            eligibility_reference_date = :referenceDate, structuring_status = :structuringStatus,
                            revision = revision + 1, updated_at = :now
                        WHERE id = :id AND status = 'DRAFT' RETURNING *
                        """)
                .params(values)
                .query(this::map)
                .single();
    }

    public long currentVersion(long announcementId) {
        return jdbc.sql(
                        """
                        SELECT COALESCE(MAX(version), 0) FROM announcement_rule_sets
                        WHERE announcement_id = ? AND status = 'PUBLISHED'
                        """)
                .param(announcementId)
                .query(Long.class)
                .single();
    }

    public void deletePublished(List<Long> announcementIds) {
        jdbc.sql(
                        "DELETE FROM announcement_rule_sets WHERE announcement_id IN (:ids) AND"
                            + " status = 'PUBLISHED'")
                .param("ids", announcementIds)
                .update();
    }

    public StoredRuleSet publish(long id, long version, String snapshot, Instant now) {
        return jdbc.sql(
                        """
                        UPDATE announcement_rule_sets SET status = 'PUBLISHED', version = ?, evaluation_snapshot =
                        ?::jsonb,
                            published_at = ?, updated_at = ? WHERE id = ? AND status = 'DRAFT' RETURNING *
                        """)
                .params(
                        version,
                        snapshot,
                        now.atOffset(ZoneOffset.UTC),
                        now.atOffset(ZoneOffset.UTC),
                        id)
                .query(this::map)
                .single();
    }

    public String snapshot(long ruleSetId) {
        return jdbc.sql(
                        "SELECT evaluation_snapshot::text FROM announcement_rule_sets WHERE id = ?"
                            + " AND status = 'PUBLISHED'")
                .param(ruleSetId)
                .query(String.class)
                .single();
    }

    private Map<String, Object> metadata(RuleSetMetadata metadata, Instant now) {
        Map<String, Object> values = new HashMap<>();
        values.put("recruitmentKind", metadata.recruitmentKind());
        values.put("applicantKind", metadata.applicantKind());
        values.put("relaxation", metadata.hasEligibilityRelaxation());
        values.put("sourceUrl", metadata.sourceUrl());
        values.put("sourcePublishedAt", metadata.sourcePublishedAt());
        values.put("collectedAt", metadata.collectedAt());
        values.put("referenceDate", metadata.eligibilityReferenceDate());
        values.put("structuringStatus", metadata.structuringStatus());
        values.put("now", now.atOffset(ZoneOffset.UTC));
        return values;
    }

    private StoredRuleSet map(ResultSet result, int index) throws SQLException {
        RuleSetMetadata metadata =
                new RuleSetMetadata(
                        result.getString("recruitment_kind"),
                        result.getString("applicant_kind"),
                        result.getBoolean("has_eligibility_relaxation"),
                        result.getString("source_url"),
                        result.getObject("source_published_at", OffsetDateTime.class),
                        result.getObject("collected_at", OffsetDateTime.class),
                        result.getObject("eligibility_reference_date", LocalDate.class),
                        result.getString("structuring_status"));
        Instant publishedAt = null;
        if (result.getObject("published_at") != null) {
            publishedAt = result.getObject("published_at", OffsetDateTime.class).toInstant();
        }
        return new StoredRuleSet(
                result.getLong("id"),
                result.getLong("announcement_id"),
                result.getString("status"),
                result.getLong("version"),
                result.getLong("revision"),
                metadata,
                result.getObject("updated_at", OffsetDateTime.class).toInstant(),
                publishedAt);
    }
}

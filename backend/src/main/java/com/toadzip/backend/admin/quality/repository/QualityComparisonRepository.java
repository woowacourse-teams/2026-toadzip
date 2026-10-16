package com.toadzip.backend.admin.quality.repository;

import com.toadzip.backend.admin.quality.domain.QualityResource;
import com.toadzip.backend.ingest.source.dto.IngestSourceCategory;
import com.toadzip.backend.ingest.source.repository.IngestSourceRows;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class QualityComparisonRepository {
    private static final String NORMALIZED_PAYLOAD =
            "raw_payload::jsonb - 'id' - 'source_id' - 'collected_at' - 'source_order' "
                    + "- 'collection_record_id' - 'consecutive_miss_count' - 'last_seen_run_id' - 'last_seen_at'";
    public static final String SOURCE_ROWS = Arrays.stream(IngestSourceCategory.values())
            .map(category -> "SELECT '" + category.name() + "'::text category, source_key, "
                    + NORMALIZED_PAYLOAD + " raw FROM ("
                    + IngestSourceRows.query(category) + ") rows")
            .collect(Collectors.joining(" UNION ALL "));
    private final JdbcClient jdbc;

    public QualityComparisonRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Link> links(QualityResource resource, long id, String target) {
        return selection("SELECT category, source_key, evidence_note FROM admin_comparison_sources "
                + "WHERE resource = :resource AND resource_id = :id AND target = :target "
                + "ORDER BY category, source_key", resource, id, target)
                .query((row, index) -> new Link(IngestSourceCategory.valueOf(row.getString(1)),
                        row.getString(2), row.getString(3))).list();
    }

    public void link(QualityResource resource, long id, String target, IngestSourceCategory category,
            String key, String note, String actor) {
        selection("""
                INSERT INTO admin_comparison_sources
                (resource, resource_id, target, category, source_key, evidence_note, actor)
                VALUES (:resource, :id, :target, :category, :key, :note, :actor)
                """, resource, id, target).param("category", category.name()).param("key", key)
                .param("note", note).param("actor", actor).update();
    }

    public int unlink(QualityResource resource, long id, String target, IngestSourceCategory category, String key) {
        return selection("DELETE FROM admin_comparison_sources WHERE resource = :resource AND resource_id = :id "
                + "AND target = :target AND category = :category AND source_key = :key", resource, id, target)
                .param("category", category.name()).param("key", key).update();
    }

    public List<String> automaticAnnouncementKeys(IngestSourceCategory category, String identifier, String panId) {
        return jdbc.sql("SELECT DISTINCT source_key FROM (" + IngestSourceRows.query(category) + ") source "
                        + "WHERE source_key = :identifier OR (raw_payload::jsonb ->> 'pblanc_id' = :identifier) "
                        + "OR (:pan <> '' AND (raw_payload::jsonb ->> 'pan_id' = :pan "
                        + "OR raw_payload::jsonb ->> 'PAN_ID' = :pan)) ORDER BY source_key")
                .param("identifier", identifier).param("pan", panId).query(String.class).list();
    }

    public String fingerprint(IngestSourceCategory category, String key) {
        return jdbc.sql("SELECT COALESCE(jsonb_agg(raw ORDER BY raw::text), '[]'::jsonb)::text FROM (SELECT "
                + NORMALIZED_PAYLOAD + " raw FROM (" + IngestSourceRows.query(category)
                + ") sources WHERE source_key = :key) current_rows")
                .param("key", key).query(String.class).single();
    }

    public void check(QualityResource resource, long id, String target, String evidence, String values,
            String note, String actor) {
        selection("""
                INSERT INTO admin_comparison_checks
                (resource, resource_id, target, evidence, checked_values, evidence_note, actor)
                VALUES (:resource, :id, :target, CAST(:evidence AS jsonb), CAST(:values AS jsonb), :note, :actor)
                """, resource, id, target).param("evidence", evidence).param("values", values)
                .param("note", note).param("actor", actor).update();
    }

    private JdbcClient.StatementSpec selection(String sql, QualityResource resource, long id, String target) {
        return jdbc.sql(sql).param("resource", resource.name()).param("id", id).param("target", target);
    }

    public record Link(IngestSourceCategory category, String sourceKey, String evidenceNote) { }
}

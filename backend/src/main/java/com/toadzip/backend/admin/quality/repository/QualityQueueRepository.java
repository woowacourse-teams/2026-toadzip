package com.toadzip.backend.admin.quality.repository;

import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.admin.quality.dto.QualityQueueItem;
import com.toadzip.backend.housing.repository.ComplexVerificationSql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class QualityQueueRepository {
    private static final String SOURCE_CHANGED = """
            EXISTS (SELECT 1 FROM (
              SELECT DISTINCT ON (resource, resource_id, target) * FROM admin_comparison_checks
              ORDER BY resource, resource_id, target, id DESC
            ) checked WHERE checked.resource = source.resource AND checked.resource_id = source.id
              AND EXISTS (SELECT 1 FROM jsonb_array_elements(checked.evidence) AS saved(value)
                WHERE saved.value -> 'rows' IS DISTINCT FROM (
                  SELECT COALESCE(jsonb_agg(raw ORDER BY raw::text), '[]'::jsonb) FROM (
            """ + QualityComparisonRepository.SOURCE_ROWS + """
                  ) current_source WHERE category = saved.value ->> 'category'
                    AND source_key = saved.value ->> 'sourceKey'
                )))
            """;
    private static final String COMPLEX = """
            SELECT 'COMPLEX'::text resource, c.id, c.name, c.admin_updated_at,
              array_remove(ARRAY[
                CASE WHEN c.completion_date IS NULL THEN '준공일' END,
                CASE WHEN NULLIF(c.heating_type, '') IS NULL THEN '난방 유형' END,
                CASE WHEN NULLIF(c.housing_type, '') IS NULL THEN '건물 유형' END,
                CASE WHEN NULLIF(c.corridor_type, '') IS NULL THEN '복도 유형' END,
                CASE WHEN c.elevator_installed IS NULL THEN '엘리베이터' END], NULL) missing_fields,
              array_remove(ARRAY[
                CASE WHEN c.source_review_required THEN 'SOURCE_MISMATCH' END,
                CASE WHEN verification_review.id IS NULL THEN 'UNREVIEWED' END,
                CASE WHEN verification_review.outcome = 'ON_HOLD' THEN 'ON_HOLD' END,
                CASE WHEN verification_review.id IS NOT NULL AND NOT (
            """ + ComplexVerificationSql.CURRENT_VALUES + """
                @> verification_review.checked_values) THEN 'STALE' END], NULL) review_reasons
            FROM housing_complexes c
            """ + ComplexVerificationSql.LATEST_REVIEW + " WHERE NOT c.admin_deleted";
    private static final String ANNOUNCEMENT = """
            SELECT 'ANNOUNCEMENT'::text resource, c.id, c.name, c.admin_updated_at,
              array_remove(ARRAY[
                CASE WHEN NULLIF(c.original_url, '') IS NULL THEN '공식 원문 URL' END,
                CASE WHEN NOT c.application_schedule_reviewed THEN '확인된 접수 일정' END,
                CASE WHEN NOT EXISTS (SELECT 1 FROM supply_rows r WHERE r.announcement_id = c.id)
                  THEN '공급정보' END,
                CASE WHEN EXISTS (SELECT 1 FROM supply_rows r WHERE r.announcement_id = c.id
                  AND (r.housing_complex_id IS NULL OR r.housing_type_id IS NULL)) THEN '단지·주택형 연결' END
              ], NULL) missing_fields,
              array_remove(ARRAY[CASE WHEN c.source_review_required THEN 'SOURCE_MISMATCH' END], NULL) review_reasons
            FROM announcements c WHERE NOT c.admin_deleted
            """;
    private static final String FROM = """
             FROM (
              SELECT source.*, review_reasons || CASE WHEN cardinality(missing_fields) > 0
                THEN ARRAY['MISSING'] ELSE ARRAY[]::text[] END || CASE WHEN
            """ + SOURCE_CHANGED + """
                THEN ARRAY['SOURCE_CHANGED'] ELSE ARRAY[]::text[] END reasons
              FROM (
            """ + COMPLEX + " UNION ALL " + ANNOUNCEMENT + """
              ) source
             ) queue WHERE cardinality(reasons) > 0
               AND (:resource = '' OR resource = :resource)
               AND (:reason = '' OR :reason = ANY(reasons))
               AND lower(name) LIKE :keyword ESCAPE '!'
            """;
    private final JdbcClient jdbc;

    public QualityQueueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public AdminPage<QualityQueueItem> search(String resource, String reason, String keyword, int page, int size) {
        String pattern = "%" + keyword.toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        long count = statement("SELECT count(*)" + FROM, resource, reason, pattern).query(Long.class).single();
        var items = statement("SELECT *" + FROM + """
                 ORDER BY CASE WHEN 'SOURCE_CHANGED' = ANY(reasons) OR 'STALE' = ANY(reasons) THEN 0
                   WHEN 'SOURCE_MISMATCH' = ANY(reasons) THEN 1
                   WHEN 'MISSING' = ANY(reasons) THEN 2 ELSE 3 END,
                 cardinality(missing_fields) DESC, admin_updated_at DESC NULLS LAST, resource, id DESC
                 LIMIT :size OFFSET :offset
                """, resource, reason, pattern).param("size", size).param("offset", (long) page * size)
                .query((row, index) -> item(row)).list();
        return new AdminPage<>(items, page, ((long) page + 1) * size < count, count,
                Math.toIntExact((count + size - 1) / size));
    }

    private JdbcClient.StatementSpec statement(String sql, String resource, String reason, String keyword) {
        return jdbc.sql(sql).param("resource", resource).param("reason", reason).param("keyword", keyword);
    }

    private QualityQueueItem item(ResultSet row) throws SQLException {
        var updated = row.getTimestamp("admin_updated_at");
        java.time.Instant instant = null;
        if (updated != null) {
            instant = updated.toInstant();
        }
        return new QualityQueueItem(row.getString("resource"), row.getLong("id"), row.getString("name"),
                Arrays.asList((String[]) row.getArray("reasons").getArray()),
                Arrays.asList((String[]) row.getArray("missing_fields").getArray()), instant);
    }
}

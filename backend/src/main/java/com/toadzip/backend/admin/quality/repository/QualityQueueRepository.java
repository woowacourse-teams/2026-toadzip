package com.toadzip.backend.admin.quality.repository;

import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.announcement.repository.SupplySourceQueryRepository;
import com.toadzip.backend.announcement.repository.SupplyReviewSql;
import com.toadzip.backend.admin.quality.dto.QualityQueueItem;
import com.toadzip.backend.admin.quality.dto.QualityQueueFilter;
import com.toadzip.backend.announcement.repository.ApplicationScheduleSql;
import com.toadzip.backend.housing.repository.ComplexVerificationSql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class QualityQueueRepository {
    public static final String SOURCE_CHANGED = """
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
              c.provider, NULL::text application_status,
              c.source_complex_identifier source_identifier, NULL::text alternate_source_identifier,
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
            SELECT 'ANNOUNCEMENT'::text resource, c.id, c.name, c.admin_updated_at, c.provider,
            """ + ApplicationScheduleSql.status("c", null) + """
              AS application_status,
              c.source_announcement_identifier source_identifier, c.lh_pan_id alternate_source_identifier,
              array_remove(ARRAY[
                CASE WHEN NULLIF(c.original_url, '') IS NULL THEN '공식 원문 URL' END,
                CASE WHEN NOT c.application_schedule_reviewed THEN '확인된 접수 일정' END,
                CASE WHEN NOT EXISTS (SELECT 1 FROM supply_rows r WHERE NOT r.admin_excluded AND r.announcement_id = c.id)
                  THEN '공급정보' END,
                CASE WHEN EXISTS (SELECT 1 FROM supply_rows r WHERE NOT r.admin_excluded AND r.announcement_id = c.id
                  AND (r.housing_complex_id IS NULL OR r.housing_type_id IS NULL)) THEN '단지·주택형 연결' END
              ], NULL) missing_fields,
              array_remove(ARRAY[CASE WHEN c.source_review_required THEN 'SOURCE_MISMATCH' END,
                CASE WHEN EXISTS (SELECT 1 FROM supply_rows r WHERE r.announcement_id = c.id AND
            """ + SupplySourceQueryRepository.MISSING + """
                ) THEN 'SOURCE_CHANGED' END], NULL) review_reasons
            FROM announcements c WHERE NOT c.admin_deleted
            """;
    private static final String SUPPLY_REVIEW_REASONS = """
            (SELECT COALESCE(array_agg(DISTINCT status), ARRAY[]::text[]) FROM (
            """ + SupplyReviewSql.EVALUATED + """
            ) supply_reviews WHERE source.resource = 'ANNOUNCEMENT' AND announcement_id = source.id
              AND latest AND status IN ('ON_HOLD', 'STALE'))
            """;
    // SOURCE_CHANGED의 출처: 데이터 검증에서 확인한 원천의 변경(COMPARISON_CHECK)과 공급행 원천 소실(SUPPLY_SOURCE).
    private static final String WITH_QUEUE = """
            WITH source AS MATERIALIZED (
              SELECT * FROM (
            """ + COMPLEX + " UNION ALL " + ANNOUNCEMENT + """
              ) source
              WHERE (:resource = '' OR resource = :resource)
                AND lower(name) LIKE :keyword ESCAPE '!'
                AND (:sourceIdentifier = '' OR source_identifier = :sourceIdentifier
                  OR alternate_source_identifier = :sourceIdentifier)
                AND (CAST(:announcementId AS bigint) IS NULL OR resource = 'ANNOUNCEMENT' AND id = :announcementId)
                AND (CAST(:complexId AS bigint) IS NULL OR resource = 'COMPLEX' AND id = :complexId
                  OR resource = 'ANNOUNCEMENT' AND EXISTS (SELECT 1 FROM supply_rows linked
                    WHERE linked.announcement_id = source.id AND NOT linked.admin_excluded
                      AND linked.housing_complex_id = :complexId))
                AND (:provider = '' OR provider IN (:provider, :providerLegacy))
                AND (:applicationStatus = '' OR resource = 'ANNOUNCEMENT' AND application_status = :applicationStatus)
            ), staged AS MATERIALIZED (
              SELECT source.*,
            """ + SUPPLY_REVIEW_REASONS + """
                supply_review_reasons,
            """ + SOURCE_CHANGED + """
                comparison_changed
              FROM source
            ), queue AS MATERIALIZED (
              SELECT staged.*, review_reasons || supply_review_reasons
                || CASE WHEN cardinality(missing_fields) > 0 THEN ARRAY['MISSING'] ELSE ARRAY[]::text[] END
                || CASE WHEN comparison_changed THEN ARRAY['SOURCE_CHANGED'] ELSE ARRAY[]::text[] END reasons,
                CASE WHEN comparison_changed THEN ARRAY['COMPARISON_CHECK'] ELSE ARRAY[]::text[] END
                || CASE WHEN 'SOURCE_CHANGED' = ANY(review_reasons) THEN ARRAY['SUPPLY_SOURCE'] ELSE ARRAY[]::text[] END
                  source_changes
              FROM staged
            ), matches AS MATERIALIZED (
              SELECT * FROM queue WHERE cardinality(reasons) > 0 AND (:reason = '' OR :reason = ANY(reasons))
            )
            """;
    private final JdbcClient jdbc;

    public QualityQueueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public AdminPage<QualityQueueItem> search(QualityQueueFilter filter, LocalDate today, int page, int size) {
        String pattern = "%" + filter.keyword().toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return statement(WITH_QUEUE + """
                SELECT page.*, total.total_elements FROM (SELECT count(*) total_elements FROM matches) total
                LEFT JOIN LATERAL (
                 SELECT * FROM matches
                 ORDER BY CASE WHEN 'SOURCE_CHANGED' = ANY(reasons) OR 'STALE' = ANY(reasons) THEN 0
                   WHEN 'SOURCE_MISMATCH' = ANY(reasons) THEN 1
                   WHEN 'MISSING' = ANY(reasons) THEN 2 ELSE 3 END,
                 cardinality(missing_fields) DESC, admin_updated_at DESC NULLS LAST, resource, id DESC
                 LIMIT :size OFFSET :offset
                ) page ON true
                ORDER BY CASE WHEN 'SOURCE_CHANGED' = ANY(page.reasons) OR 'STALE' = ANY(page.reasons) THEN 0
                  WHEN 'SOURCE_MISMATCH' = ANY(page.reasons) THEN 1
                  WHEN 'MISSING' = ANY(page.reasons) THEN 2 ELSE 3 END,
                cardinality(page.missing_fields) DESC, page.admin_updated_at DESC NULLS LAST, page.resource, page.id DESC
                """, filter, pattern, today).param("size", size).param("offset", (long) page * size)
                .query(result -> {
                    return page(result, page, size);
                });
    }

    private AdminPage<QualityQueueItem> page(ResultSet result, int page, int size) throws SQLException {
        List<QualityQueueItem> items = new ArrayList<>();
        long count = 0;
        while (result.next()) {
            count = result.getLong("total_elements");
            if (result.getObject("id") != null) {
                items.add(item(result));
            }
        }
        return new AdminPage<>(items, page, ((long) page + 1) * size < count, count,
                Math.toIntExact((count + size - 1) / size));
    }

    private JdbcClient.StatementSpec statement(String sql, QualityQueueFilter filter, String keyword, LocalDate today) {
        String providerLegacy = "";
        if (filter.provider() != null) { providerLegacy = filter.provider().legacyStoredValue(); }
        return jdbc.sql(sql).param("resource", code(filter.resource())).param("reason", filter.reason())
                .param("keyword", keyword).param("sourceIdentifier", filter.sourceIdentifier())
                .param("announcementId", filter.announcementId())
                .param("complexId", filter.complexId()).param("provider", code(filter.provider()))
                .param("providerLegacy", providerLegacy).param("applicationStatus", code(filter.applicationStatus()))
                .param("today", today);
    }

    private String code(Enum<?> value) {
        if (value == null) { return ""; }
        return value.name();
    }

    private QualityQueueItem item(ResultSet row) throws SQLException {
        var updated = row.getTimestamp("admin_updated_at");
        java.time.Instant instant = null;
        if (updated != null) {
            instant = updated.toInstant();
        }
        return new QualityQueueItem(row.getString("resource"), row.getLong("id"), row.getString("name"),
                Arrays.asList((String[]) row.getArray("reasons").getArray()),
                Arrays.asList((String[]) row.getArray("missing_fields").getArray()),
                Arrays.asList((String[]) row.getArray("source_changes").getArray()), instant);
    }
}

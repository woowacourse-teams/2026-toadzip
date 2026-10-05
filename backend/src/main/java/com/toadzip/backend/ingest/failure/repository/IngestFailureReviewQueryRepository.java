package com.toadzip.backend.ingest.failure.repository;

import com.toadzip.backend.ingest.failure.dto.IngestFailureReviewPageResponse;
import com.toadzip.backend.ingest.failure.dto.IngestFailureReviewPageResponse.Item;
import com.toadzip.backend.ingest.failure.dto.IngestFailureReviewPageResponse.Product;
import com.toadzip.backend.ingest.source.dto.IngestSourceCategory;
import com.toadzip.backend.ingest.source.repository.IngestSourceRows;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class IngestFailureReviewQueryRepository {

    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {};
    private static final String PAGE_SQL = """
            WITH latest_collection_failures AS (
                SELECT DISTINCT ON (source, request_description) *
                FROM external_data_collection_failures
                ORDER BY source, request_description, id DESC
            ), failures AS (%s), filtered AS (
                SELECT * FROM failures
                WHERE domain_value = :domain AND (:category = 'all' OR category = :category)
                    AND (:status = 'ALL' OR status = :status)
            ), page_failures AS (
                SELECT * FROM filtered
                ORDER BY last_occurred_at DESC, id DESC, category ASC
                LIMIT :size OFFSET :offset
            )
            SELECT totals.total_elements, failure.*,
                COALESCE(complex_source.name, announcement_source.name,
                         complex.name, announcement.name, failure.target_name) AS target_name_value,
                COALESCE(complex.id, announcement.id) AS product_id,
                COALESCE(complex.name, announcement.name) AS product_name,
                COALESCE(complex.admin_deleted, announcement.admin_deleted) AS product_deleted,
                CASE WHEN complex.id IS NOT NULL THEN 'complexes'
                     WHEN announcement.id IS NOT NULL THEN 'announcements' END AS resource_type,
                CASE WHEN complex.id IS NOT NULL THEN
                    complex.latitude IS NOT NULL AND complex.longitude IS NOT NULL END AS has_coordinates,
                CASE WHEN complex.id IS NOT NULL THEN
                    (SELECT COUNT(*) FROM housing_types WHERE housing_complex_id = complex.id)
                END AS housing_type_count,
                CASE WHEN announcement.id IS NOT NULL THEN
                    (SELECT COUNT(DISTINCT housing_complex_id) FROM supply_rows
                     WHERE announcement_id = announcement.id) END AS linked_complex_count,
                CASE WHEN announcement.id IS NOT NULL THEN
                    (SELECT COUNT(*) FROM supply_rows WHERE announcement_id = announcement.id) END AS supply_row_count,
                CASE WHEN announcement.id IS NOT NULL THEN
                    (SELECT COUNT(*) FROM announcement_application_schedules WHERE announcement_id = announcement.id)
                END AS application_schedule_count,
                CASE WHEN announcement.id IS NOT NULL THEN
                    (SELECT COUNT(*) FROM announcement_attachments WHERE announcement_id = announcement.id)
                END AS attachment_count,
                announcement.application_start_date, announcement.application_end_date,
                announcement.application_schedule_reviewed,
                COALESCE(NOT complex.admin_deleted, NOT announcement.admin_deleted) AS public_detail_available,
                CASE WHEN complex.id IS NOT NULL THEN NOT complex.admin_deleted
                        AND complex.latitude IS NOT NULL AND complex.longitude IS NOT NULL
                     WHEN announcement.id IS NOT NULL THEN NOT announcement.admin_deleted
                        AND (announcement.status IN ('ORIGINAL', '원공고')
                             OR (announcement.status IN ('CORRECTION', '정정공고')
                                 AND announcement.previous_announcement_id IS NOT NULL))
                        AND NOT successor.exists_value END AS public_list_eligible,
                ARRAY_REMOVE(ARRAY[
                    CASE WHEN COALESCE(complex.admin_deleted, announcement.admin_deleted) THEN 'ADMIN_DELETED' END,
                    CASE WHEN complex.id IS NOT NULL AND (complex.latitude IS NULL OR complex.longitude IS NULL)
                         THEN 'MISSING_COORDINATES' END,
                    CASE WHEN announcement.id IS NOT NULL
                            AND announcement.status NOT IN ('ORIGINAL', '원공고', 'CORRECTION', '정정공고')
                         THEN 'NOT_LISTABLE_PUBLICATION_TYPE' END,
                    CASE WHEN announcement.status IN ('CORRECTION', '정정공고')
                            AND announcement.previous_announcement_id IS NULL
                         THEN 'MISSING_PREVIOUS_ANNOUNCEMENT' END,
                    CASE WHEN successor.exists_value THEN 'HAS_SUCCESSOR' END
                ], NULL) AS list_exclusion_reasons
            FROM (SELECT COUNT(*) AS total_elements FROM filtered) totals
            LEFT JOIN page_failures failure ON TRUE
            LEFT JOIN myhome_complex_links link ON failure.category = 'complex'
                AND NULLIF(BTRIM(failure.source_complex_identifier), '') IS NOT NULL
                AND link.source_complex_identifier = failure.source_complex_identifier
            LEFT JOIN housing_complexes complex ON failure.category = 'complex'
                AND NULLIF(BTRIM(failure.source_complex_identifier), '') IS NOT NULL
                AND (complex.id = link.housing_complex_id
                    OR (link.housing_complex_id IS NULL
                        AND complex.source_complex_identifier = failure.source_complex_identifier))
            LEFT JOIN announcements announcement ON failure.category IN ('announcement', 'enrichment')
                AND NULLIF(BTRIM(failure.source_announcement_identifier), '') IS NOT NULL
                AND announcement.source_announcement_identifier = failure.source_announcement_identifier
            LEFT JOIN LATERAL (
                SELECT source.name FROM (%s) source
                WHERE failure.category = 'complex' AND source.source_key = failure.source_key
                ORDER BY source.collected_at DESC NULLS LAST, source.id DESC LIMIT 1
            ) complex_source ON TRUE
            LEFT JOIN LATERAL (
                SELECT source.name FROM (%s) source
                WHERE failure.category IN ('announcement', 'enrichment') AND source.source_key = failure.source_key
                ORDER BY (source.raw_payload::jsonb ->> 'active')::boolean DESC NULLS LAST,
                         source.collected_at DESC NULLS LAST, source.id DESC LIMIT 1
            ) announcement_source ON TRUE
            LEFT JOIN LATERAL (
                SELECT EXISTS(SELECT 1 FROM announcements next_announcement
                    WHERE next_announcement.previous_announcement_id = announcement.id
                        AND next_announcement.admin_deleted = FALSE) AS exists_value
            ) successor ON TRUE
            ORDER BY failure.last_occurred_at DESC, failure.id DESC, failure.category ASC
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public IngestFailureReviewQueryRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public IngestFailureReviewPageResponse findReviews(
            String domain, String category, String status, int page, int size
    ) {
        List<ReviewRow> rows = jdbc.sql(PAGE_SQL.formatted(failureUnion(),
                        IngestSourceRows.query(IngestSourceCategory.MYHOME_COMPLEX),
                        IngestSourceRows.query(IngestSourceCategory.MYHOME_ANNOUNCEMENT)))
                .param("domain", domain).param("category", category).param("status", status)
                .param("size", size).param("offset", (long) page * size)
                .query((row, index) -> reviewRow(row)).list();
        long total = rows.getFirst().total();
        List<Item> items = rows.stream().map(ReviewRow::item).filter(item -> item != null).toList();
        long totalPages = (total + size - 1) / size;
        return new IngestFailureReviewPageResponse(items, page, total, totalPages, (long) page + 1 < totalPages);
    }

    private String failureUnion() {
        return failureQueries().stream().map(this::failureSelect).collect(Collectors.joining(" UNION ALL "));
    }

    private List<FailureQuery> failureQueries() {
        return List.of(
                new FailureQuery("latest_collection_failures", "collection", """
                        CASE WHEN source IN ('MYHOME_COMPLEX', 'LH_LEASE_CATALOG')
                            THEN 'complex' ELSE 'announcement' END
                        """, "source", "request_description", "NULL::text", "NULL::text", "NULL::text",
                        "error_type", "reason", "resolved_at", "resolved_execution_id",
                        "jsonb_build_object('attemptCount', attempt_count, 'errorType', error_type)"),
                new FailureQuery("myhome_complex_mapping_failures", "complex", "'complex'", "'MYHOME_COMPLEX'",
                        "source_key", "source_complex_identifier", "NULL::text", "NULL::text", "reason", "detail",
                        "last_resolved_at", "last_resolved_execution_id", "'{}'::jsonb"),
                new FailureQuery("lh_household_enrichment_failures", "household", "'complex'", "'LH_LEASE_CATALOG'",
                        "source_key", "NULL::text", "NULL::text", "complex_name", "reason", "detail",
                        "last_resolved_at", "last_resolved_execution_id",
                        "jsonb_build_object('areaName', area_name, 'supplyTypeName', supply_type_name, "
                                + "'complexName', complex_name)"),
                new FailureQuery("myhome_announcement_mapping_failures", "announcement", "'announcement'",
                        "'MYHOME_ANNOUNCEMENT'", "source_key", "NULL::text", "source_announcement_identifier",
                        "NULL::text", "reason", "detail", "last_resolved_at", "last_resolved_execution_id",
                        "jsonb_build_object('sourceHouseSerialNumber', source_house_serial_number)"),
                new FailureQuery("lh_announcement_enrichment_failures", "enrichment", "'announcement'",
                        "'LH_ANNOUNCEMENT'", "source_key", "NULL::text", "source_announcement_identifier",
                        "NULL::text", "reason", "detail", "last_resolved_at", "last_resolved_execution_id",
                        "jsonb_build_object('panId', pan_id)")
        );
    }

    private String failureSelect(FailureQuery query) {
        return """
                SELECT id, '%s'::text AS category, %s AS domain_value, %s AS source, %s AS source_key,
                    %s AS source_complex_identifier, %s AS source_announcement_identifier, %s AS target_name,
                    %s AS reason, %s AS detail, status, occurred_at, last_occurred_at, occurrence_count,
                    recurrence_count, %s AS last_resolved_at, first_execution_id, last_execution_id,
                    %s AS last_resolved_execution_id, jsonb_strip_nulls(%s)::text AS metadata
                FROM %s
                """.formatted(query.category(), query.domain(), query.source(), query.sourceKey(),
                        query.complexIdentifier(), query.announcementIdentifier(), query.targetName(), query.reason(),
                        query.detail(), query.resolvedAt(), query.resolvedExecutionId(), query.metadata(),
                        query.table());
    }

    private ReviewRow reviewRow(ResultSet row) throws SQLException {
        if (row.getObject("id") == null) {
            return new ReviewRow(row.getLong("total_elements"), null);
        }
        Product product = product(row);
        Item item = new Item(row.getLong("id"), row.getString("category"), row.getString("source_key"),
                row.getString("source"), row.getString("reason"), row.getString("detail"), row.getString("status"),
                instant(row, "occurred_at"), instant(row, "last_occurred_at"), row.getInt("occurrence_count"),
                row.getInt("recurrence_count"), instant(row, "last_resolved_at"), uuid(row, "first_execution_id"),
                uuid(row, "last_execution_id"), uuid(row, "last_resolved_execution_id"),
                row.getString("source_complex_identifier"), row.getString("source_announcement_identifier"),
                row.getString("target_name_value"), json.readValue(row.getString("metadata"), METADATA_TYPE),
                productLinkStatus(row, product), product);
        return new ReviewRow(row.getLong("total_elements"), item);
    }

    private Product product(ResultSet row) throws SQLException {
        if (row.getObject("product_id") == null) {
            return null;
        }
        return new Product(row.getLong("product_id"), row.getString("product_name"), row.getString("resource_type"),
                row.getBoolean("product_deleted"), row.getObject("has_coordinates", Boolean.class),
                row.getObject("housing_type_count", Long.class), row.getObject("linked_complex_count", Long.class),
                row.getObject("supply_row_count", Long.class), row.getObject("application_schedule_count", Long.class),
                row.getObject("attachment_count", Long.class), row.getObject("application_start_date", LocalDate.class),
                row.getObject("application_end_date", LocalDate.class),
                row.getObject("application_schedule_reviewed", Boolean.class),
                row.getBoolean("public_detail_available"),
                row.getBoolean("public_list_eligible"),
                List.copyOf(Arrays.asList((String[]) row.getArray("list_exclusion_reasons").getArray())));
    }

    private String productLinkStatus(ResultSet row, Product product) throws SQLException {
        if (product != null) {
            return "EXISTS";
        }
        String identifier = row.getString("source_complex_identifier");
        if (identifier == null) {
            identifier = row.getString("source_announcement_identifier");
        }
        if (identifier == null || identifier.isBlank()) {
            return "UNKNOWN";
        }
        return "NOT_FOUND";
    }

    private Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp timestamp = row.getTimestamp(column);
        if (timestamp == null) {
            return null;
        }
        return timestamp.toInstant();
    }

    private UUID uuid(ResultSet row, String column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    private record ReviewRow(long total, Item item) {
    }

    private record FailureQuery(
            String table, String category, String domain, String source, String sourceKey, String complexIdentifier,
            String announcementIdentifier, String targetName, String reason, String detail, String resolvedAt,
            String resolvedExecutionId, String metadata
    ) {
    }
}

package com.toadzip.backend.ingest.correction.repository;

import com.toadzip.backend.ingest.correction.dto.IngestWorkspacePage;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class IngestWorkspaceRepository {
    private final JdbcClient jdbc;

    public IngestWorkspacePage find(String domain, String status, int page, int size) {
        if (!List.of("ALL", "WAITING", "FAILED", "INCOMPLETE", "READY").contains(status)) {
            throw new InvalidIngestRequestException("조회 상태가 올바르지 않습니다.");
        }
        String targets = switch (domain) {
            case "complex" -> complexes();
            case "announcement" -> announcements();
            default -> throw new InvalidIngestRequestException("단지 또는 공고를 선택해 주세요.");
        };
        var counts = jdbc.sql("WITH targets AS (" + targets + ") SELECT status, COUNT(*) AS amount "
                        + "FROM targets GROUP BY status")
                .query((row, index) -> Map.entry(row.getString("status"), row.getLong("amount"))).list();
        var summary = new java.util.LinkedHashMap<String, Long>();
        List.of("WAITING", "FAILED", "INCOMPLETE", "READY").forEach(value -> summary.put(value, 0L));
        counts.forEach(entry -> summary.put(entry.getKey(), entry.getValue()));
        long total = summary.values().stream().mapToLong(Long::longValue).sum();
        if (!status.equals("ALL")) {
            total = summary.get(status);
        }
        var items = jdbc.sql("WITH targets AS (" + targets + ") SELECT * FROM targets "
                        + "WHERE (:status = 'ALL' OR status = :status) "
                        + "ORDER BY collected_at DESC NULLS LAST, identifier ASC LIMIT :size OFFSET :offset")
                .param("status", status).param("size", size).param("offset", (long) page * size)
                .query((row, index) -> new IngestWorkspacePage.Item(row.getString("identifier"),
                        row.getString("name"), row.getObject("product_id", Long.class), row.getString("status"),
                        row.getString("detail"), instant(row.getTimestamp("collected_at"))))
                .list();
        return new IngestWorkspacePage(items, summary, page, total, ((long) page + 1) * size < total);
    }

    private java.time.Instant instant(java.sql.Timestamp value) {
        if (value == null) {
            return null;
        }
        return value.toInstant();
    }

    private String complexes() {
        return """
                WITH source_groups AS (
                    SELECT hsmp_sn::text || ':' || CASE BTRIM(suply_ty_nm)
                        WHEN '행복주택' THEN 'HAPPY_HOUSING' WHEN '국민임대' THEN 'NATIONAL_RENTAL'
                        WHEN '영구임대' THEN 'PERMANENT_RENTAL' WHEN '5년임대' THEN 'PUBLIC_RENTAL_5Y'
                        WHEN '10년임대' THEN 'PUBLIC_RENTAL_10Y' WHEN '50년임대' THEN 'PUBLIC_RENTAL_50Y'
                        WHEN '장기전세' THEN 'LONG_TERM_JEONSE' WHEN '통합공공임대' THEN 'INTEGRATED_PUBLIC_RENTAL'
                        WHEN '재개발임대' THEN 'REDEVELOPMENT_RENTAL'
                        WHEN '기타' THEN 'ETC'
                        ELSE COALESCE(suply_ty_nm, 'UNKNOWN') END AS identifier,
                        MAX(hsmp_nm) AS name, MAX(collected_at) AS collected_at
                    FROM myhome_complex_source_rows
                    WHERE hsmp_sn IS NOT NULL AND BTRIM(suply_ty_nm) IS DISTINCT FROM '매입임대'
                    GROUP BY hsmp_sn, suply_ty_nm
                ), linked_sources AS (
                    SELECT source.*, COALESCE(link.housing_complex_id, canonical.id) AS housing_complex_id
                    FROM source_groups source
                    LEFT JOIN myhome_complex_links link ON link.source_complex_identifier = source.identifier
                    LEFT JOIN housing_complexes canonical ON canonical.source_complex_identifier = source.identifier
                ), known AS (
                    SELECT COALESCE(source.identifier, product.source_complex_identifier) AS identifier,
                        COALESCE(product.name, source.name) AS name, product.id AS product_id,
                        product.latitude, product.longitude, source.collected_at,
                        COALESCE(correction.last_failure, failure.detail) AS failure_detail,
                        (SELECT COUNT(*) FROM housing_types WHERE housing_complex_id = product.id) AS type_count
                    FROM linked_sources source
                    FULL JOIN housing_complexes product ON product.id = source.housing_complex_id
                    LEFT JOIN ingest_corrections correction ON correction.id = 'complex:' ||
                        COALESCE(source.identifier, product.source_complex_identifier)
                    LEFT JOIN LATERAL (SELECT detail FROM myhome_complex_mapping_failures
                        WHERE source_complex_identifier =
                            COALESCE(source.identifier, product.source_complex_identifier)
                            AND status = 'PENDING' ORDER BY last_occurred_at DESC, id DESC LIMIT 1) failure ON TRUE
                    WHERE product.id IS NULL OR NOT product.admin_deleted
                ) SELECT identifier, name, product_id, collected_at,
                    CASE WHEN failure_detail IS NOT NULL THEN 'FAILED' WHEN product_id IS NULL THEN 'WAITING'
                        WHEN latitude IS NULL OR longitude IS NULL OR type_count = 0 THEN 'INCOMPLETE'
                        ELSE 'READY' END AS status,
                    CASE WHEN failure_detail IS NOT NULL THEN failure_detail
                        WHEN product_id IS NULL THEN '정제 전 원천'
                        WHEN latitude IS NULL OR longitude IS NULL THEN '좌표 누락'
                        WHEN type_count = 0 THEN '주택형 누락' ELSE '핵심 항목 확인됨' END AS detail FROM known
                """;
    }

    private String announcements() {
        return """
                WITH source_groups AS (
                    SELECT pblanc_id AS identifier, MAX(pblanc_nm) AS name, MAX(collected_at) AS collected_at
                    FROM myhome_announcement_source_rows WHERE active AND pblanc_id IS NOT NULL GROUP BY pblanc_id
                ), known AS (
                    SELECT COALESCE(source.identifier, product.source_announcement_identifier) AS identifier,
                        COALESCE(product.name, source.name) AS name, product.id AS product_id,
                        product.application_start_date, product.application_end_date, source.collected_at,
                        COALESCE(correction.last_failure, failure.detail) AS failure_detail,
                        (SELECT COUNT(*) FROM supply_rows WHERE announcement_id = product.id) AS supply_count,
                        (SELECT COUNT(*) FROM supply_rows WHERE announcement_id = product.id
                            AND (housing_complex_id IS NULL OR housing_type_id IS NULL)) AS missing_link_count
                    FROM source_groups source
                    FULL JOIN announcements product ON product.source_announcement_identifier = source.identifier
                    LEFT JOIN ingest_corrections correction ON correction.id = 'announcement:' ||
                        COALESCE(source.identifier, product.source_announcement_identifier)
                    LEFT JOIN LATERAL (SELECT detail FROM (
                        SELECT detail, last_occurred_at, id FROM myhome_announcement_mapping_failures
                        WHERE source_announcement_identifier =
                            COALESCE(source.identifier, product.source_announcement_identifier)
                            AND status = 'PENDING'
                        UNION ALL SELECT detail, last_occurred_at, id FROM lh_announcement_enrichment_failures
                        WHERE source_announcement_identifier =
                            COALESCE(source.identifier, product.source_announcement_identifier)
                            AND status = 'PENDING') failures
                        ORDER BY last_occurred_at DESC, id DESC LIMIT 1) failure ON TRUE
                    WHERE product.id IS NULL OR NOT product.admin_deleted
                ) SELECT identifier, name, product_id, collected_at,
                    CASE WHEN failure_detail IS NOT NULL THEN 'FAILED' WHEN product_id IS NULL THEN 'WAITING'
                        WHEN application_start_date IS NULL OR application_end_date IS NULL OR supply_count = 0
                            OR missing_link_count > 0 THEN 'INCOMPLETE' ELSE 'READY' END AS status,
                    CASE WHEN failure_detail IS NOT NULL THEN failure_detail
                        WHEN product_id IS NULL THEN '정제 전 원천'
                        WHEN application_start_date IS NULL OR application_end_date IS NULL THEN '신청 기간 누락'
                        WHEN supply_count = 0 THEN '공급행 누락'
                        WHEN missing_link_count > 0 THEN '단지·주택형 연결 누락'
                        ELSE '핵심 항목 확인됨' END AS detail FROM known
                """;
    }
}

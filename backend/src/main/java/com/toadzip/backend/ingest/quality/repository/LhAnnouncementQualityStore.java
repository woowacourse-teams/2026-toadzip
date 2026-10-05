package com.toadzip.backend.ingest.quality.repository;

import com.toadzip.backend.ingest.collection.lh.supply.domain.LhSupplySnapshot;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.CollectionCoverage;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.Connection;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.Coverage;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.HeldRequest;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.Schedule;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.UnlinkedLhCandidate;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LhAnnouncementQualityStore {

    private static final Pattern FINGERPRINT = Pattern.compile("proposedFingerprint=([0-9a-f]{64})");

    private static final String UNLINKED_LH_LEASE_CATALOG = """
            FROM lh_announcement_catalog_entries catalog
            WHERE catalog.present_in_latest_catalog AND catalog.upper_announcement_type_code = '06'
            """;

    private final JdbcClient jdbc;

    public LhAnnouncementQualityStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public LhAnnouncementQualityResponse snapshot(Instant observedAt, CollectionCoverage supplyCollectionCoverage,
            CollectionCoverage detailCollectionCoverage, Set<String> linkedPanIds, Set<String> currentSourceKeys) {
        Map<String, Long> amountReasons = preservedAmountReasons(currentSourceKeys);
        return new LhAnnouncementQualityResponse(
                observedAt,
                connection(),
                amounts(),
                schedules(),
                supplyCollectionCoverage,
                detailCollectionCoverage,
                unlinkedLhLeaseCatalogCount(linkedPanIds),
                unlinkedLhCandidates(linkedPanIds),
                preservedSourceRequestCount(),
                preservedReasons(),
                amountReasons.values().stream().mapToLong(Long::longValue).sum(),
                amountReasons,
                heldRequests()
        );
    }

    private Connection connection() {
        Connection counts = jdbc.sql("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE row.housing_complex_id IS NOT NULL) AS complex_linked,
                       COUNT(*) FILTER (WHERE row.housing_type_id IS NOT NULL) AS type_linked
                FROM supply_rows row
                JOIN announcements announcement ON announcement.id = row.announcement_id
                WHERE announcement.provider = 'LH' AND NOT announcement.admin_deleted
                """).query((result, index) -> new Connection(
                result.getLong("total"), result.getLong("complex_linked"), result.getLong("type_linked"), Map.of()
        )).single();
        Map<String, Long> reasons = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT COALESCE(NULLIF(row.matching_failure_reason, ''), '미연결 사유 없음') AS reason,
                       COUNT(*) AS row_count
                FROM supply_rows row
                JOIN announcements announcement ON announcement.id = row.announcement_id
                WHERE announcement.provider = 'LH' AND NOT announcement.admin_deleted
                  AND (row.housing_complex_id IS NULL OR row.housing_type_id IS NULL)
                GROUP BY 1 ORDER BY row_count DESC, reason
                """).query((result, index) -> {
            reasons.put(result.getString("reason"), result.getLong("row_count"));
            return true;
        }).list();
        return new Connection(counts.total(), counts.complexLinked(), counts.housingTypeLinked(), reasons);
    }

    private Coverage amounts() {
        return jdbc.sql("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE EXISTS (
                           SELECT 1 FROM supply_targets target
                           WHERE target.supply_row_id = row.id
                             AND target.rental_deposit IS NOT NULL AND target.monthly_rent IS NOT NULL
                       )) AS fulfilled
                FROM supply_rows row
                JOIN announcements announcement ON announcement.id = row.announcement_id
                WHERE announcement.provider = 'LH' AND NOT announcement.admin_deleted
                """).query((result, index) -> new Coverage(
                result.getLong("total"), result.getLong("fulfilled")
        )).single();
    }

    private Schedule schedules() {
        return jdbc.sql("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE announcement.application_schedule_reviewed) AS reviewed,
                       COUNT(*) FILTER (WHERE EXISTS (
                           SELECT 1 FROM announcement_application_schedules schedule
                           WHERE schedule.announcement_id = announcement.id
                       )) AS with_schedule
                FROM announcements announcement
                WHERE announcement.provider = 'LH' AND NOT announcement.admin_deleted
                """).query((result, index) -> new Schedule(
                result.getLong("total"), result.getLong("reviewed"), result.getLong("with_schedule")
        )).single();
    }

    private long unlinkedLhLeaseCatalogCount(Set<String> linkedPanIds) {
        return unlinkedCatalogQuery("SELECT COUNT(*) ", "", linkedPanIds).query(Long.class).single();
    }

    private List<UnlinkedLhCandidate> unlinkedLhCandidates(Set<String> linkedPanIds) {
        return unlinkedCatalogQuery("SELECT catalog.pan_id, catalog.source_key, catalog.changed_at ",
                " ORDER BY catalog.changed_at DESC, catalog.id DESC LIMIT 50", linkedPanIds)
                .query((result, index) -> new UnlinkedLhCandidate(
                        result.getString("pan_id"), result.getString("source_key"), instant(result, "changed_at")
                )).list();
    }

    private JdbcClient.StatementSpec unlinkedCatalogQuery(String select, String suffix, Set<String> linkedPanIds) {
        if (linkedPanIds.isEmpty()) {
            return jdbc.sql(select + UNLINKED_LH_LEASE_CATALOG + suffix);
        }
        return jdbc.sql(select + UNLINKED_LH_LEASE_CATALOG + " AND catalog.pan_id NOT IN (:linkedPanIds)" + suffix)
                .param("linkedPanIds", linkedPanIds);
    }

    private long preservedSourceRequestCount() {
        return jdbc.sql("""
                SELECT COUNT(*) FROM external_data_collection_failures failure
                WHERE failure.source = 'LH_ANNOUNCEMENT_SUPPLY' AND failure.status = 'PENDING'
                  AND failure.error_type IN ('IncompleteLhSupplyReplacementException',
                                             'EmptyLhSupplyReplacementException')
                """).query(Long.class).single();
    }

    private Map<String, Long> preservedReasons() {
        Map<String, Long> reasons = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT failure.error_type, COUNT(*) AS request_count
                FROM external_data_collection_failures failure
                WHERE failure.source = 'LH_ANNOUNCEMENT_SUPPLY' AND failure.status = 'PENDING'
                  AND failure.error_type IN ('IncompleteLhSupplyReplacementException',
                                             'EmptyLhSupplyReplacementException')
                GROUP BY failure.error_type ORDER BY failure.error_type
                """).query((result, index) -> {
            reasons.put(result.getString("error_type"), result.getLong("request_count"));
            return true;
        }).list();
        return reasons;
    }

    private Map<String, Long> preservedAmountReasons(Set<String> currentSourceKeys) {
        Map<String, Long> reasons = new LinkedHashMap<>();
        // MyHomeAnnouncementSupplyRowGroups와 같은 display_order, id 순서로 확장 행의 원천을 찾는다.
        String currentSources = currentSourceKeys.isEmpty() ? "NULL" : ":currentSourceKeys";
        var query = jdbc.sql("""
                WITH current_sources AS (
                    SELECT unnest(ARRAY[%s]::text[]) AS source_key
                ), preserved AS (
                    SELECT CASE
                        WHEN EXISTS (
                            SELECT 1 FROM myhome_announcement_mapping_failures failure
                            WHERE failure.source_announcement_identifier = announcement.source_announcement_identifier
                              AND source_group.source_key IN (SELECT source_key FROM current_sources)
                              AND failure.source_key IN (SELECT source_key FROM current_sources)
                              AND failure.status = 'PENDING'
                              AND failure.reason NOT IN ('COMPLEX_NOT_FOUND', 'AMBIGUOUS_COMPLEX',
                                                         'HOUSING_TYPE_NOT_FOUND', 'AMBIGUOUS_HOUSING_TYPE')
                        ) THEN 'MYHOME_MAPPING_REJECTED'
                        WHEN EXISTS (
                            SELECT 1 FROM lh_announcement_enrichment_failures failure
                            WHERE failure.source_announcement_identifier = announcement.source_announcement_identifier
                              AND source_group.source_key IN (SELECT source_key FROM current_sources)
                              AND failure.source_key IN (SELECT source_key FROM current_sources)
                              AND failure.status = 'PENDING' AND failure.source_key NOT LIKE 'LH:%%'
                        ) THEN 'LH_ENRICHMENT_REJECTED'
                        ELSE target.lh_amount_preserved_reason END AS reason
                    FROM supply_targets target
                    JOIN supply_rows row ON row.id = target.supply_row_id
                    JOIN announcements announcement ON announcement.id = row.announcement_id
                    LEFT JOIN LATERAL (
                        SELECT root.source_supply_row_identifier AS source_key
                        FROM supply_rows root
                        WHERE root.announcement_id = row.announcement_id
                          AND NOT starts_with(root.source_supply_row_identifier,
                                              announcement.source_announcement_identifier || ':LH:')
                          AND (root.display_order, root.id) <= (row.display_order, row.id)
                        ORDER BY root.display_order DESC, root.id DESC LIMIT 1
                    ) source_group ON true
                    WHERE announcement.provider = 'LH' AND NOT announcement.admin_deleted
                      AND target.source_supply_target_identifier LIKE 'LH:%%'
                      AND target.rental_deposit IS NOT NULL AND target.monthly_rent IS NOT NULL
                )
                SELECT reason, COUNT(*) AS target_count FROM preserved
                WHERE reason IS NOT NULL GROUP BY reason ORDER BY reason
                """.formatted(currentSources));
        if (!currentSourceKeys.isEmpty()) {
            query = query.param("currentSourceKeys", currentSourceKeys);
        }
        query.query((result, index) -> {
            reasons.put(result.getString("reason"), result.getLong("target_count"));
            return true;
        }).list();
        return reasons;
    }

    private List<HeldRequest> heldRequests() {
        return jdbc.sql("""
                SELECT request_description, reason, error_type, last_occurred_at
                FROM external_data_collection_failures
                WHERE source = 'LH_ANNOUNCEMENT_SUPPLY' AND status = 'PENDING'
                  AND error_type IN ('IncompleteLhSupplyReplacementException',
                                     'EmptyLhSupplyReplacementException')
                ORDER BY last_occurred_at DESC, id DESC LIMIT 50
                """).query((result, index) -> new HeldRequest(
                result.getString("request_description"), result.getString("reason"),
                instant(result, "last_occurred_at"), fingerprint(result)
        )).list();
    }

    private String fingerprint(ResultSet result) throws SQLException {
        if ("EmptyLhSupplyReplacementException".equals(result.getString("error_type"))) {
            return LhSupplySnapshot.fingerprint(List.of());
        }
        Matcher matcher = FINGERPRINT.matcher(result.getString("reason"));
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        java.sql.Timestamp timestamp = result.getTimestamp(column);
        if (timestamp == null) {
            return null;
        }
        return timestamp.toInstant();
    }
}

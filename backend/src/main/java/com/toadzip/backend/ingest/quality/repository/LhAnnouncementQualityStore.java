package com.toadzip.backend.ingest.quality.repository;

import com.toadzip.backend.ingest.lh.collection.supply.domain.HeldLhSupplyReplacement;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.CollectionCoverage;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.Connection;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.Coverage;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.HeldRequest;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.Schedule;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.UnlinkedLhCandidate;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LhAnnouncementQualityStore {

    private final JdbcClient jdbc;

    public LhAnnouncementQualityStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public LhAnnouncementQualityResponse snapshot(Instant observedAt, CollectionCoverage supplyCollectionCoverage,
            CollectionCoverage detailCollectionCoverage, Set<String> unlinkedCatalogSourceKeys) {
        return new LhAnnouncementQualityResponse(
                observedAt,
                connection(),
                amounts(),
                schedules(),
                supplyCollectionCoverage,
                detailCollectionCoverage,
                unlinkedCatalogSourceKeys.size(),
                unlinkedLhCandidates(unlinkedCatalogSourceKeys),
                preservedSourceRequestCount(),
                preservedReasons(),
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
                           SELECT 1 FROM supply_row_targets cell
                           JOIN lease_term_members member ON member.supply_row_target_id = cell.id
                           JOIN lease_terms lease ON lease.id = member.lease_terms_id
                           WHERE cell.supply_row_id = row.id
                             AND lease.rental_deposit IS NOT NULL AND lease.monthly_rent IS NOT NULL
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

    private List<UnlinkedLhCandidate> unlinkedLhCandidates(Set<String> unlinkedCatalogSourceKeys) {
        if (unlinkedCatalogSourceKeys.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT catalog.pan_id, catalog.source_key, catalog.changed_at
                FROM lh_announcement_catalog_entries catalog
                WHERE catalog.source_key IN (:sourceKeys)
                ORDER BY catalog.changed_at DESC, catalog.id DESC LIMIT 50
                """).param("sourceKeys", unlinkedCatalogSourceKeys)
                .query((result, index) -> new UnlinkedLhCandidate(
                        result.getString("pan_id"), result.getString("source_key"), instant(result, "changed_at")
                )).list();
    }

    private long preservedSourceRequestCount() {
        return jdbc.sql("""
                SELECT COUNT(*) FROM external_data_collection_failures failure
                WHERE failure.source = 'LH_ANNOUNCEMENT_SUPPLY' AND failure.status = 'PENDING'
                  AND failure.error_type IN (:errorTypes)
                """).param("errorTypes", HeldLhSupplyReplacement.errorTypes()).query(Long.class).single();
    }

    private Map<String, Long> preservedReasons() {
        Map<String, Long> reasons = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT failure.error_type, COUNT(*) AS request_count
                FROM external_data_collection_failures failure
                WHERE failure.source = 'LH_ANNOUNCEMENT_SUPPLY' AND failure.status = 'PENDING'
                  AND failure.error_type IN (:errorTypes)
                GROUP BY failure.error_type ORDER BY failure.error_type
                """).param("errorTypes", HeldLhSupplyReplacement.errorTypes()).query((result, index) -> {
            reasons.put(result.getString("error_type"), result.getLong("request_count"));
            return true;
        }).list();
        return reasons;
    }

    private List<HeldRequest> heldRequests() {
        return jdbc.sql("""
                SELECT request_description, reason, error_type, last_occurred_at
                FROM external_data_collection_failures
                WHERE source = 'LH_ANNOUNCEMENT_SUPPLY' AND status = 'PENDING'
                  AND error_type IN (:errorTypes)
                ORDER BY last_occurred_at DESC, id DESC LIMIT 50
                """).param("errorTypes", HeldLhSupplyReplacement.errorTypes()).query((result, index) -> new HeldRequest(
                result.getString("request_description"), result.getString("reason"),
                instant(result, "last_occurred_at"), fingerprint(result)
        )).list();
    }

    private String fingerprint(ResultSet result) throws SQLException {
        return HeldLhSupplyReplacement.proposedFingerprint(result.getString("error_type"), result.getString("reason"))
                .orElse(null);
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        if (timestamp == null) {
            return null;
        }
        return timestamp.toInstant();
    }
}

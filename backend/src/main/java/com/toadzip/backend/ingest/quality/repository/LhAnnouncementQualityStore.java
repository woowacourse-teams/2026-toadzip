package com.toadzip.backend.ingest.quality.repository;

import com.toadzip.backend.ingest.collection.domain.LhSupplySnapshot;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.Connection;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.Coverage;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.Freshness;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.HeldRequest;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.Schedule;
import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse.UnlinkedLhCandidate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LhAnnouncementQualityStore {

    private static final Pattern FINGERPRINT = Pattern.compile("proposedFingerprint=([0-9a-f]{64})");

    private static final String UNLINKED_LH_LEASE_CATALOG = """
            FROM lh_announcement_catalog_source catalog
            WHERE catalog.present_in_latest_catalog AND catalog.upper_announcement_type_code = '06'
              AND NOT EXISTS (
                  SELECT 1 FROM lh_announcement_collection_links link
                  JOIN myhome_announcement_source myhome
                    ON myhome.source_key = link.source_announcement_key
                  WHERE link.pan_id = catalog.pan_id AND myhome.active
                    AND (
                        NOT EXISTS (
                            SELECT 1 FROM myhome_announcement_source observed
                            WHERE observed.pblanc_id = myhome.pblanc_id AND observed.active
                              AND observed.last_seen_run_id IS NOT NULL
                              AND observed.collected_at IS NOT NULL
                        )
                        OR myhome.last_seen_run_id IN (
                            SELECT observed.last_seen_run_id
                            FROM myhome_announcement_source observed
                            WHERE observed.pblanc_id = myhome.pblanc_id AND observed.active
                              AND observed.last_seen_run_id IS NOT NULL
                              AND observed.collected_at IS NOT NULL
                            ORDER BY observed.collected_at DESC, observed.id DESC LIMIT 1
                        )
                    )
              )
            """;

    private final JdbcClient jdbc;

    public LhAnnouncementQualityStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public LhAnnouncementQualityResponse snapshot(Instant observedAt, Instant freshSince) {
        return new LhAnnouncementQualityResponse(
                observedAt,
                connection(),
                amounts(),
                schedules(),
                freshness("LH_ANNOUNCEMENT_SUPPLY", freshSince),
                freshness("LH_ANNOUNCEMENT_DETAIL", freshSince),
                unlinkedLhLeaseCatalogCount(),
                unlinkedLhCandidates(),
                preservedSourceRequestCount(),
                preservedReasons(),
                preservedAmountTargetCount(),
                preservedAmountReasons(),
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

    private Freshness freshness(String source, Instant freshSince) {
        return jdbc.sql("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE completed_at >= :freshSince) AS fresh,
                       MAX(completed_at) AS latest
                FROM lh_announcement_collection_checkpoints
                WHERE source = :source
                """).param("source", source).param("freshSince", java.sql.Timestamp.from(freshSince))
                .query((result, index) -> new Freshness(
                        result.getLong("total"), result.getLong("fresh"), instant(result, "latest")
                )).single();
    }

    private long unlinkedLhLeaseCatalogCount() {
        return jdbc.sql("SELECT COUNT(*) " + UNLINKED_LH_LEASE_CATALOG).query(Long.class).single();
    }

    private List<UnlinkedLhCandidate> unlinkedLhCandidates() {
        return jdbc.sql("SELECT catalog.pan_id, catalog.source_key, catalog.changed_at "
                + UNLINKED_LH_LEASE_CATALOG
                + " ORDER BY catalog.changed_at DESC, catalog.id DESC LIMIT 50")
                .query((result, index) -> new UnlinkedLhCandidate(
                result.getString("pan_id"), result.getString("source_key"), instant(result, "changed_at")
        )).list();
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

    private long preservedAmountTargetCount() {
        return jdbc.sql("""
                SELECT COUNT(*) FROM supply_targets target
                JOIN supply_rows row ON row.id = target.supply_row_id
                JOIN announcements announcement ON announcement.id = row.announcement_id
                WHERE announcement.provider = 'LH' AND NOT announcement.admin_deleted
                  AND target.lh_amount_preserved_reason IS NOT NULL
                """).query(Long.class).single();
    }

    private Map<String, Long> preservedAmountReasons() {
        Map<String, Long> reasons = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT target.lh_amount_preserved_reason AS reason, COUNT(*) AS target_count
                FROM supply_targets target
                JOIN supply_rows row ON row.id = target.supply_row_id
                JOIN announcements announcement ON announcement.id = row.announcement_id
                WHERE announcement.provider = 'LH' AND NOT announcement.admin_deleted
                  AND target.lh_amount_preserved_reason IS NOT NULL
                GROUP BY target.lh_amount_preserved_reason
                """).query((result, index) -> {
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

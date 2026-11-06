package com.toadzip.backend.admin.repository;

import com.toadzip.backend.admin.dto.OperationsOverview;
import com.toadzip.backend.announcement.repository.ApplicationScheduleSql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OperationsOverviewRepository {
    private static final String RECRUITING = """
            EXISTS (SELECT 1 FROM supply_rows r JOIN announcements a ON a.id = r.announcement_id
                WHERE r.housing_complex_id = h.id AND r.admin_excluded = false
                  AND a.admin_deleted = false AND a.release_status = 'PUBLISHED'
                  AND NOT EXISTS (SELECT 1 FROM announcements successor
                    WHERE successor.previous_announcement_id = a.id AND successor.admin_deleted = false
                      AND successor.release_status = 'PUBLISHED')
                  AND %s IN ('BEFORE_APPLICATION', 'APPLYING'))
            """.formatted(ApplicationScheduleSql.status("a", "h.id"));
    private static final String COMPLEXES = """
            SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE %s) AS recruiting,
                COUNT(*) FILTER (WHERE h.latitude IS NULL OR h.longitude IS NULL) AS missing_coordinates,
                COUNT(*) FILTER (WHERE h.source_review_required) AS review_required,
                COUNT(*) FILTER (WHERE h.provider IN ('LH', '한국토지주택공사')) AS lh,
                COUNT(*) FILTER (WHERE h.provider IN ('SH', '서울주택도시공사')) AS sh,
                COUNT(*) FILTER (WHERE h.provider IN ('GH', '경기주택도시공사')) AS gh
            FROM housing_complexes h WHERE h.admin_deleted = false
            """.formatted(RECRUITING);
    private static final String ANNOUNCEMENTS = """
            WITH overview AS (
                SELECT COALESCE(%s, 'UNKNOWN') AS application_status,
                    CASE WHEN a.application_schedule_reviewed THEN NOT EXISTS (
                        SELECT 1 FROM announcement_application_schedules s WHERE s.announcement_id = a.id)
                    ELSE a.application_start_date IS NULL OR a.application_end_date IS NULL END AS schedule_missing,
                    NOT EXISTS (SELECT 1 FROM supply_rows r
                        WHERE r.announcement_id = a.id AND r.admin_excluded = false) AS supply_missing,
                    a.release_status = 'UNCONFIRMED' AS unconfirmed
                FROM announcements a WHERE a.admin_deleted = false
            )
            SELECT COUNT(*) FILTER (WHERE application_status = 'BEFORE_APPLICATION') AS upcoming,
                COUNT(*) FILTER (WHERE application_status = 'APPLYING') AS applying,
                COUNT(*) FILTER (WHERE application_status = 'CLOSED') AS closed,
                COUNT(*) FILTER (WHERE application_status = 'CONDITIONAL') AS conditional,
                COUNT(*) FILTER (WHERE application_status = 'CANCELLED') AS cancelled,
                COUNT(*) FILTER (WHERE application_status = 'UNKNOWN') AS unknown,
                COUNT(*) FILTER (WHERE schedule_missing OR supply_missing OR unconfirmed) AS attention,
                COUNT(*) FILTER (WHERE schedule_missing) AS schedule_missing,
                COUNT(*) FILTER (WHERE supply_missing) AS supply_missing,
                COUNT(*) FILTER (WHERE unconfirmed) AS unconfirmed
            FROM overview
            """.formatted(ApplicationScheduleSql.status("a", null));
    private static final String COLLECTIONS = """
            WITH agencies(agency, types) AS (VALUES
                ('LH', ARRAY['ANNOUNCEMENT_COLLECTION', 'ANNOUNCEMENT_SYNC', 'ANNOUNCEMENT_REGISTRATION']),
                ('SH', ARRAY['SH_ANNOUNCEMENT_COLLECTION']), ('GH', ARRAY['GH_ANNOUNCEMENT_COLLECTION'])
            )
            SELECT agencies.agency, successful.finished_at AS last_success_at,
                latest.started_at AS latest_started_at, latest.status AS latest_status
            FROM agencies
            LEFT JOIN LATERAL (SELECT started_at, status FROM data_pipeline_executions execution
                WHERE execution.type = ANY(agencies.types) ORDER BY started_at DESC, id DESC LIMIT 1) latest ON TRUE
            LEFT JOIN LATERAL (SELECT finished_at FROM data_pipeline_executions execution
                WHERE execution.type = ANY(agencies.types) AND execution.status = 'COMPLETED'
                  AND finished_at IS NOT NULL ORDER BY finished_at DESC, id DESC LIMIT 1) successful ON TRUE
            """;

    private final JdbcClient jdbc;

    public OperationsOverviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public OperationsOverview.Complexes complexes(LocalDate today, Instant asOf) {
        return jdbc.sql(COMPLEXES).param("today", today).query((row, index) -> new OperationsOverview.Complexes(
                row.getLong("total"), row.getLong("recruiting"), row.getLong("missing_coordinates"),
                row.getLong("review_required"), Map.of("LH", row.getLong("lh"), "SH", row.getLong("sh"),
                        "GH", row.getLong("gh")), asOf)).single();
    }

    public OperationsOverview.Announcements announcements(LocalDate today, Instant asOf) {
        return jdbc.sql(ANNOUNCEMENTS).param("today", today).query((row, index) -> new OperationsOverview.Announcements(
                Map.of("BEFORE_APPLICATION", row.getLong("upcoming"), "APPLYING", row.getLong("applying"),
                        "CLOSED", row.getLong("closed"), "CONDITIONAL", row.getLong("conditional"),
                        "CANCELLED", row.getLong("cancelled"), "UNKNOWN", row.getLong("unknown")),
                row.getLong("attention"), Map.of("SCHEDULE_MISSING", row.getLong("schedule_missing"),
                        "SUPPLY_MISSING", row.getLong("supply_missing"), "UNCONFIRMED", row.getLong("unconfirmed")),
                asOf)).single();
    }

    public OperationsOverview.Users users(LocalDate start, LocalDate today, LocalDateTime now, int days, Instant asOf) {
        var trend = jdbc.sql("""
                SELECT day::date AS day, COUNT(member.id) AS count
                FROM generate_series(CAST(:start AS date), CAST(:today AS date), INTERVAL '1 day') day
                LEFT JOIN users member ON member.created_at >= day AND member.created_at < day + INTERVAL '1 day'
                    AND member.created_at <= :now
                GROUP BY day ORDER BY day
                """).param("start", start).param("today", today).param("now", now)
                .query((row, index) -> new OperationsOverview.DayCount(row.getObject("day", LocalDate.class),
                        row.getLong("count"))).list();
        long total = jdbc.sql("SELECT COUNT(*) FROM users").query(Long.class).single();
        long previous = jdbc.sql("SELECT COUNT(*) FROM users WHERE created_at >= :start AND created_at < :end")
                .param("start", start.minusDays(days).atStartOfDay()).param("end", start.atStartOfDay())
                .query(Long.class).single();
        return new OperationsOverview.Users(total, trend.stream().mapToLong(OperationsOverview.DayCount::count).sum(),
                previous, trend, asOf);
    }

    public OperationsOverview.Collections collections(Instant asOf) {
        return new OperationsOverview.Collections(jdbc.sql(COLLECTIONS).query((row, index) ->
                new OperationsOverview.Collection(row.getString("agency"), instant(row, "last_success_at"),
                        instant(row, "latest_started_at"), row.getString("latest_status"))).list(), asOf);
    }

    private Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, OffsetDateTime.class);
        if (value == null) {
            return null;
        }
        return value.toInstant();
    }
}

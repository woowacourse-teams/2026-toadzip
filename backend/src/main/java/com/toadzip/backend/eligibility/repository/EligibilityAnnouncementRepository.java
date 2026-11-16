package com.toadzip.backend.eligibility.repository;

import com.toadzip.backend.announcement.repository.ApplicationScheduleSql;
import com.toadzip.backend.eligibility.exception.EligibilityNotFoundException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class EligibilityAnnouncementRepository {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JdbcClient jdbc;

    public EligibilityAnnouncementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Long> lockAncestors(long announcementId) {
        List<Long> ids =
                jdbc.sql(
                                """
                                WITH RECURSIVE chain AS (
                                    SELECT id, previous_announcement_id, ARRAY[id] AS visited FROM announcements WHERE
                                    id = ?
                                    UNION ALL
                                    SELECT a.id, a.previous_announcement_id, c.visited || a.id
                                    FROM announcements a JOIN chain c ON a.id = c.previous_announcement_id
                                    WHERE NOT a.id = ANY(c.visited)
                                )
                                SELECT a.id FROM announcements a WHERE a.id IN (SELECT id FROM chain) ORDER BY a.id
                                FOR UPDATE
                                """)
                        .param(announcementId)
                        .query(Long.class)
                        .list();
        if (ids.isEmpty()) {
            throw new EligibilityNotFoundException();
        }
        return ids;
    }

    public Current latest(long announcementId) {
        return latest(announcementId, true);
    }

    public Current latestMetadata(long announcementId) {
        return latest(announcementId, false);
    }

    private Current latest(long announcementId, boolean includeContent) {
        String contentColumn = "NULL::text";
        if (includeContent) {
            contentColumn = "r.content::text";
        }
        return jdbc.sql(
                        """
                        WITH RECURSIVE chain AS (
                            SELECT id, ARRAY[id] AS visited FROM announcements WHERE id = ?
                            UNION ALL
                            SELECT a.id, c.visited || a.id FROM announcements a
                            JOIN chain c ON a.previous_announcement_id = c.id WHERE NOT a.id = ANY(c.visited)
                        )
                        SELECT a.*, r.id AS rule_set_id, r.version AS rule_set_version,
                            r.schema_version AS content_schema_version,
                            %s AS snapshot
                        FROM announcements a JOIN chain c ON c.id = a.id
                        LEFT JOIN basic_comparison_rule_sets r ON r.announcement_id = a.id AND r.status = 'PUBLISHED'
                        ORDER BY cardinality(c.visited) DESC LIMIT 1
                        """.formatted(contentColumn))
                .param(announcementId)
                .query(this::map)
                .optional()
                .orElseThrow(EligibilityNotFoundException::new);
    }

    /**
     * 요청 ID마다 정정 체인의 첫 공고(root)·최신 공고·공개 내용·접수 상태를 한 쿼리로 읽는다.
     * {@link #rootId}, {@link #latest}, {@link #applicationStatus}를 공고마다 부르는 것과 같은 결과다. 없는 ID는 결과에 없다.
     */
    public Map<Long, Latest> latestByRequest(List<Long> announcementIds, LocalDate today) {
        Map<Long, Latest> latest = new HashMap<>();
        jdbc.sql("""
                        WITH RECURSIVE requested AS (
                            SELECT DISTINCT id AS requested_id, comparison_root_id(id) AS root_id
                            FROM unnest(CAST(:ids AS bigint[])) AS t(id)
                        ), chain AS (
                            SELECT root_id, root_id AS id, ARRAY[root_id] AS visited
                            FROM (SELECT DISTINCT root_id FROM requested WHERE root_id IS NOT NULL) roots
                            UNION ALL
                            SELECT c.root_id, a.id, c.visited || a.id FROM announcements a
                            JOIN chain c ON a.previous_announcement_id = c.id WHERE NOT a.id = ANY(c.visited)
                        ), newest AS (
                            SELECT DISTINCT ON (root_id) root_id, id FROM chain
                            ORDER BY root_id, cardinality(visited) DESC, id DESC
                        )
                        SELECT q.requested_id, n.root_id, a.*, r.id AS rule_set_id, r.version AS rule_set_version,
                            r.schema_version AS content_schema_version, r.content::text AS snapshot,
                            COALESCE(%s, 'UNKNOWN') AS current_application_status
                        FROM requested q JOIN newest n ON n.root_id = q.root_id
                        JOIN announcements a ON a.id = n.id
                        LEFT JOIN basic_comparison_rule_sets r ON r.announcement_id = a.id AND r.status = 'PUBLISHED'
                        """.formatted(ApplicationScheduleSql.status("a", null)))
                .param("ids", announcementIds.toArray(Long[]::new))
                .param("today", today)
                .query((row, index) -> latest.put(row.getLong("requested_id"), new Latest(row.getLong("root_id"),
                        map(row, index), row.getString("current_application_status"))))
                .list();
        return latest;
    }

    /** Keyset batches using the same verified-date policy as announcement search. */
    public List<Current> activeAfter(long afterId, Instant now, int batchSize) {
        return jdbc.sql(
                        """
                        SELECT a.*, r.id AS rule_set_id, r.version AS rule_set_version,
                            r.schema_version AS content_schema_version, r.content::text AS snapshot
                        FROM announcements a LEFT JOIN basic_comparison_rule_sets r
                            ON r.announcement_id = a.id AND r.status = 'PUBLISHED'
                        WHERE a.id > :after AND a.release_status = 'PUBLISHED' AND NOT a.admin_deleted
                            AND NOT EXISTS (SELECT 1 FROM announcements next WHERE next.previous_announcement_id = a.id)
                            AND %s = 'APPLYING'
                        ORDER BY a.id LIMIT :limit
                        """
                                .formatted(ApplicationScheduleSql.status("a", null)))
                .param("after", afterId)
                .param("today", now.atZone(SEOUL).toLocalDate())
                .param("limit", batchSize)
                .query(this::map)
                .list();
    }

    public long rootId(long announcementId) {
        return jdbc.sql("SELECT comparison_root_id(?)").param(announcementId).query(Long.class)
                .optional().orElseThrow(EligibilityNotFoundException::new);
    }

    public String applicationStatus(long announcementId, LocalDate today) {
        return jdbc.sql("SELECT COALESCE(" + ApplicationScheduleSql.status("a", null)
                        + ", 'UNKNOWN') FROM announcements a WHERE a.id = :id")
                .param("id", announcementId).param("today", today).query(String.class).single();
    }

    public List<Long> eligibleRootsAfter(long cursor, LocalDate today, int limit) {
        return jdbc.sql("""
                        WITH RECURSIVE chain AS (
                            SELECT id, id AS root_id, ARRAY[id] AS visited FROM announcements
                            WHERE previous_announcement_id IS NULL AND id > :cursor
                            UNION ALL
                            SELECT a.id, c.root_id, c.visited || a.id FROM announcements a
                            JOIN chain c ON a.previous_announcement_id = c.id
                            WHERE NOT a.id = ANY(c.visited) AND cardinality(c.visited) < 100
                        )
                        SELECT c.root_id FROM chain c JOIN announcements a ON a.id = c.id
                        WHERE a.release_status = 'PUBLISHED' AND NOT a.admin_deleted
                            AND NOT EXISTS (SELECT 1 FROM announcements next WHERE next.previous_announcement_id = a.id)
                            AND %s IN ('APPLYING', 'BEFORE_APPLICATION', 'CONDITIONAL')
                        ORDER BY c.root_id LIMIT :limit
                        """.formatted(ApplicationScheduleSql.status("a", null)))
                .param("cursor", cursor).param("today", today).param("limit", limit).query(Long.class).list();
    }

    private Current map(ResultSet result, int index) throws SQLException {
        return new Current(
                result.getLong("id"),
                result.getString("name"),
                result.getString("release_status"),
                result.getString("status"),
                result.getBoolean("admin_deleted"),
                result.getObject("application_start_date", LocalDate.class),
                result.getObject("application_end_date", LocalDate.class),
                result.getObject("rule_set_id", Long.class),
                result.getObject("rule_set_version", Long.class),
                result.getObject("content_schema_version", Integer.class),
                result.getString("snapshot"));
    }

    public record Latest(long rootId, Current current, String applicationStatus) {}

    public record Current(
            long id,
            String name,
            String releaseStatus,
            String publicationType,
            boolean deleted,
            LocalDate startsOn,
            LocalDate endsOn,
            Long ruleSetId,
            Long ruleSetVersion,
            Integer contentSchemaVersion,
            String snapshot) {
        public String availability() {
            if (deleted || !"PUBLISHED".equals(releaseStatus)) {
                return "NOT_PUBLISHED";
            }
            if (cancelled()) {
                return "CANCELLED";
            }
            if (ruleSetId == null) {
                return "NOT_STRUCTURED";
            }
            return "AVAILABLE";
        }

        public boolean cancelled() {
            return "취소공고".equals(publicationType) || "CANCELLATION".equals(publicationType);
        }
    }
}

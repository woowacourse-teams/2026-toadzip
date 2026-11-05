package com.toadzip.backend.eligibility.repository;

import com.toadzip.backend.eligibility.exception.EligibilityNotFoundException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

@Repository
public class EligibilityAnnouncementRepository {
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
        return jdbc.sql(
                        """
                        WITH RECURSIVE chain AS (
                            SELECT id, ARRAY[id] AS visited FROM announcements WHERE id = ?
                            UNION ALL
                            SELECT a.id, c.visited || a.id FROM announcements a
                            JOIN chain c ON a.previous_announcement_id = c.id WHERE NOT a.id = ANY(c.visited)
                        )
                        SELECT a.*, r.id AS rule_set_id, r.version AS rule_set_version,
                            r.evaluation_snapshot::text AS snapshot
                        FROM announcements a JOIN chain c ON c.id = a.id
                        LEFT JOIN announcement_rule_sets r ON r.announcement_id = a.id AND r.status = 'PUBLISHED'
                        ORDER BY cardinality(c.visited) DESC LIMIT 1
                        """)
                .param(announcementId)
                .query(this::map)
                .optional()
                .orElseThrow(EligibilityNotFoundException::new);
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
                result.getString("snapshot"));
    }

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
            String snapshot) {
        public String availability() {
            if (deleted || !"PUBLISHED".equals(releaseStatus)) {
                return "NOT_PUBLISHED";
            }
            if ("취소공고".equals(publicationType)) {
                return "CANCELLED";
            }
            if (ruleSetId == null) {
                return "NOT_STRUCTURED";
            }
            return "AVAILABLE";
        }
    }
}

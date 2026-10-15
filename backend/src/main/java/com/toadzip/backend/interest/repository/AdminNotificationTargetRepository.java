package com.toadzip.backend.interest.repository;

import com.toadzip.backend.interest.domain.NotificationTargetType;
import com.toadzip.backend.interest.dto.AdminNotificationTargets;
import com.toadzip.backend.interest.dto.AdminNotificationTargets.Target;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AdminNotificationTargetRepository {
    private static final String TARGETS = AdminNotificationQueryRepository.BASE + """
            , targets AS (
                SELECT target_type, group_id AS target_id, target_name, COUNT(DISTINCT user_id) AS active_members
                FROM base WHERE status = 'ACTIVE' AND (:targetType = '' OR target_type = :targetType)
                GROUP BY target_type, group_id, target_name
            ), totals AS (SELECT COUNT(*) AS total FROM targets), paged AS (
                SELECT * FROM targets ORDER BY active_members DESC, target_type, target_id
                LIMIT :size OFFSET :offset
            )
            SELECT totals.total, paged.* FROM totals LEFT JOIN paged ON TRUE
            ORDER BY active_members DESC, target_type, target_id
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AdminNotificationTargets search(
            NotificationTargetType type, int page, int size, Instant asOf, Map<String, String> regionGroups) {
        String typeName = "";
        if (type != null) {
            typeName = type.name();
        }
        List<Row> rows = jdbc.sql(TARGETS).param("asOf", Timestamp.from(asOf)).param("userId", null)
                .param("regionGroups", mapper.writeValueAsString(regionGroups)).param("targetType", typeName)
                .param("size", size).param("offset", (long) page * size).query((result, index) -> {
                    Target target = null;
                    if (result.getString("target_id") != null) {
                        target = new Target(NotificationTargetType.valueOf(result.getString("target_type")),
                                result.getString("target_id"), result.getString("target_name"),
                                result.getLong("active_members"));
                    }
                    return new Row(result.getLong("total"), target);
                }).list();
        long total = rows.getFirst().total();
        return new AdminNotificationTargets(rows.stream().map(Row::target).filter(target -> target != null).toList(),
                page, total, Math.toIntExact((total + size - 1) / size), asOf);
    }

    private record Row(long total, Target target) {
    }
}

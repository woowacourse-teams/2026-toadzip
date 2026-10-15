package com.toadzip.backend.interest.repository;

import com.toadzip.backend.interest.domain.AdminNotificationStatus;
import com.toadzip.backend.interest.domain.NotificationTargetType;
import com.toadzip.backend.interest.dto.AdminNotificationFilter;
import com.toadzip.backend.interest.dto.AdminNotificationPage;
import com.toadzip.backend.interest.dto.AdminNotificationPage.Item;
import com.toadzip.backend.interest.dto.AdminNotificationPage.Summary;
import com.toadzip.backend.user.dto.AdminUserProvider;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdminNotificationQueryRepository {

    static final String BASE = """
            WITH base AS (
                SELECT subscriptions.id, member.id AS user_id, member.email,
                    CASE WHEN member.login_identifier LIKE 'google:%' THEN 'GOOGLE'
                         WHEN member.login_identifier LIKE 'kakao:%' THEN 'KAKAO'
                         ELSE 'UNKNOWN' END AS provider,
                    subscriptions.target_type, subscriptions.target_id, subscriptions.expires_at,
                    CASE WHEN subscriptions.target_type = 'REGION'
                         THEN COALESCE(CAST(:regionGroups AS jsonb) ->> subscriptions.target_id,
                             subscriptions.target_id)
                         ELSE COALESCE(NULLIF(LTRIM(subscriptions.target_id,'0'),''),'0') END AS group_id,
                    COALESCE(CASE WHEN NOT complex.admin_deleted THEN complex.name END,
                             CASE WHEN NOT announcement.admin_deleted THEN announcement.name END) AS target_name,
                    notices.requested_at,
                    CASE WHEN subscriptions.target_type = 'REGION'
                         THEN jsonb_exists(CAST(:regionGroups AS jsonb), subscriptions.target_id)
                         WHEN subscriptions.target_type = 'COMPLEX'
                         THEN complex.id IS NOT NULL AND NOT complex.admin_deleted
                         ELSE announcement.id IS NOT NULL AND NOT announcement.admin_deleted END AS target_available,
                    CASE WHEN NOT subscriptions.active THEN 'CANCELLED'
                         WHEN subscriptions.expires_at <= :asOf THEN 'EXPIRED'
                         ELSE 'ACTIVE' END AS status
                FROM notification_subscriptions subscriptions
                JOIN users member ON member.id = subscriptions.user_id
                LEFT JOIN privacy_notification_notices notices USING (user_id, target_type, target_id)
                LEFT JOIN housing_complexes complex ON subscriptions.target_type = 'COMPLEX'
                    AND LTRIM(subscriptions.target_id, '0') = complex.id::text
                LEFT JOIN announcements announcement ON subscriptions.target_type = 'ANNOUNCEMENT'
                    AND LTRIM(subscriptions.target_id, '0') = announcement.id::text
                WHERE (CAST(:userId AS bigint) IS NULL OR member.id = CAST(:userId AS bigint))
            )
            """;

    private static final String SEARCH = BASE + """
            , filtered AS (
                SELECT * FROM base
                WHERE (:keyword = '' OR LOWER(COALESCE(email, '')) LIKE :pattern ESCAPE '!'
                    OR user_id = CAST(:identifier AS bigint))
                    AND (:targetType = '' OR target_type = :targetType)
                    AND (:targetId = '' OR (NOT :groupTarget AND target_id = :targetId)
                        OR (:groupTarget AND group_id = CASE WHEN target_type = 'REGION'
                            THEN COALESCE(CAST(:regionGroups AS jsonb) ->> :targetId, :targetId)
                            ELSE COALESCE(NULLIF(LTRIM(:targetId,'0'),''),'0') END))
                    AND (:status = 'ALL' OR status = :status)
                    AND (:review = 'ALL'
                        OR (:review = 'TARGET_UNAVAILABLE' AND NOT target_available)
                        OR (:review = 'REQUEST_DATE_UNKNOWN' AND requested_at IS NULL)
                        OR (:review = 'ANY' AND (NOT target_available OR requested_at IS NULL)))
            ), paged AS (
                SELECT * FROM filtered ORDER BY requested_at DESC NULLS LAST, id DESC
                LIMIT :size OFFSET :offset
            ), totals AS (
                SELECT COUNT(*) AS total_elements FROM filtered
            ), summary AS (
                SELECT COUNT(DISTINCT user_id) FILTER (WHERE status = 'ACTIVE') AS active_members,
                    COUNT(*) FILTER (WHERE status = 'ACTIVE') AS active_subscriptions FROM base
            )
            SELECT totals.total_elements, summary.active_members, summary.active_subscriptions, paged.*
            FROM totals CROSS JOIN summary LEFT JOIN paged ON TRUE
            ORDER BY paged.requested_at DESC NULLS LAST, paged.id DESC
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AdminNotificationQueryRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public AdminNotificationPage search(
            AdminNotificationFilter filter, int page, int size, Instant asOf, Map<String, String> regionGroups) {
        List<Row> rows = jdbc.sql(SEARCH).param("asOf", Timestamp.from(asOf))
                .param("userId", filter.userId()).param("keyword", filter.keyword())
                .param("identifier", numericIdentifier(filter.keyword())).param("pattern", pattern(filter.keyword()))
                .param("targetType", enumName(filter.targetType())).param("targetId", filter.targetId())
                .param("status", filter.status().name()).param("size", size).param("offset", (long) page * size)
                .param("review", filter.review().name()).param("regionGroups", mapper.writeValueAsString(regionGroups))
                .param("groupTarget", filter.groupTarget())
                .query((result, index) -> row(result, asOf)).list();
        Row totals = rows.getFirst();
        int totalPages = Math.toIntExact((totals.total() + size - 1) / size);
        List<Item> items = rows.stream().map(Row::item).filter(item -> item != null).toList();
        return new AdminNotificationPage(items, page, (long) page + 1 < totalPages,
                totals.total(), totalPages, totals.summary());
    }

    private Row row(ResultSet result, Instant asOf) throws SQLException {
        Summary summary = new Summary(result.getLong("active_members"), result.getLong("active_subscriptions"), asOf);
        Item item = null;
        if (result.getObject("id") != null) {
            item = new Item(result.getString("id"), result.getString("user_id"), result.getString("email"),
                    AdminUserProvider.valueOf(result.getString("provider")),
                    NotificationTargetType.valueOf(result.getString("target_type")), result.getString("target_id"),
                    result.getString("target_name"), AdminNotificationStatus.valueOf(result.getString("status")),
                    instant(result, "requested_at"), instant(result, "expires_at"));
        }
        return new Row(result.getLong("total_elements"), item, summary);
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        if (value == null) {
            return null;
        }
        return value.toInstant();
    }

    private String enumName(NotificationTargetType type) {
        if (type == null) {
            return "";
        }
        return type.name();
    }

    private String pattern(String keyword) {
        return "%" + keyword.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private Long numericIdentifier(String keyword) {
        if (!keyword.matches("[0-9]+")) {
            return null;
        }
        try {
            return Long.parseLong(keyword);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private record Row(long total, Item item, Summary summary) {
    }
}

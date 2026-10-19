package com.toadzip.backend.notification.repository;

import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.notification.dto.AdminNotificationDelivery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AdminNotificationDeliveryRepository {
    private static final String FROM = """
             FROM user_notifications notification
             LEFT JOIN notification_deliveries delivery ON delivery.notification_id = notification.id
             WHERE (:userId IS NULL OR notification.user_id = :userId)
               AND (:announcementId IS NULL OR notification.announcement_id = :announcementId)
               AND notification.title ILIKE :keyword ESCAPE '!'
               AND (:state = '' OR COALESCE(delivery.status, 'NO_DELIVERY') = :state)
            """;
    private final JdbcClient jdbc;

    public AdminPage<AdminNotificationDelivery> search(Long userId, Long announcementId, String state, String keyword,
            int page, int size) {
        long count = query("SELECT count(*)" + FROM, userId, announcementId, state, keyword).query(Long.class).single();
        List<AdminNotificationDelivery> rows = query("""
                SELECT notification.id AS notification_id, notification.user_id, notification.announcement_id,
                    notification.title, notification.created_at, notification.read_at, notification.deleted_at,
                    notification.expires_at, COALESCE(delivery.status, 'NO_DELIVERY') AS delivery_status,
                    delivery.id AS delivery_id, COALESCE(delivery.attempts, 0) AS attempts, delivery.last_error_code,
                    delivery.next_attempt_at, delivery.finished_at
                """ + FROM + " ORDER BY notification.created_at DESC, notification.id DESC, delivery.id DESC "
                + "LIMIT :size OFFSET :offset", userId, announcementId, state, keyword)
                .param("size", size).param("offset", (long) page * size).query((row, index) -> map(row)).list();
        return new AdminPage<>(rows, page, ((long) page + 1) * size < count, count,
                Math.toIntExact((count + size - 1) / size));
    }

    private JdbcClient.StatementSpec query(String sql, Long userId, Long announcementId, String state, String keyword) {
        return jdbc.sql(sql).param("userId", userId, java.sql.Types.BIGINT)
                .param("announcementId", announcementId, java.sql.Types.BIGINT).param("state", state).param("keyword", "%" + keyword.strip().replace("!", "!!")
                        .replace("%", "!%").replace("_", "!_") + "%");
    }

    private AdminNotificationDelivery map(ResultSet row) throws SQLException {
        return new AdminNotificationDelivery(row.getLong("notification_id"), row.getLong("user_id"),
                row.getLong("announcement_id"), row.getString("title"), row.getString("delivery_status"),
                row.getObject("delivery_id", Long.class), row.getInt("attempts"), row.getString("last_error_code"),
                instant(row, "created_at"), instant(row, "next_attempt_at"), instant(row, "finished_at"),
                instant(row, "read_at"), row.getTimestamp("deleted_at") != null, instant(row, "expires_at"));
    }

    private Instant instant(ResultSet row, String name) throws SQLException {
        var value = row.getTimestamp(name);
        if (value == null) { return null; }
        return value.toInstant();
    }
}

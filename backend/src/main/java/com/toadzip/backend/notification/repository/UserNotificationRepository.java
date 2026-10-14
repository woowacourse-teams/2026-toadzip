package com.toadzip.backend.notification.repository;

import com.toadzip.backend.notification.domain.NotificationCursor;
import com.toadzip.backend.notification.dto.NotificationPageResponse;
import com.toadzip.backend.notification.dto.NotificationPageResponse.Item;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class UserNotificationRepository {
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockUser(long userId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", (row, number) -> 0,
                "notification-user:" + userId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public InsertedNotification insertIfAbsent(long userId, long eventId, long announcementId, String title, Instant now) {
        lockUser(userId);
        List<Long> inserted = jdbc.query("""
                INSERT INTO user_notifications(user_id,event_id,announcement_id,title,created_at,expires_at)
                VALUES(?,?,?,?,?,?) ON CONFLICT(user_id,event_id) DO NOTHING RETURNING id
                """, (row, number) -> row.getLong(1), userId, eventId, announcementId, title,
                Timestamp.from(now), Timestamp.from(now.plus(Duration.ofDays(90))));
        if (!inserted.isEmpty()) {
            return new InsertedNotification(inserted.getFirst(), true);
        }
        long id = jdbc.queryForObject("SELECT id FROM user_notifications WHERE user_id=? AND event_id=?",
                Long.class, userId, eventId);
        return new InsertedNotification(id, false);
    }

    public record InsertedNotification(long id, boolean created) {
    }

    public long countUnread(long userId, Instant now) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM user_notifications WHERE user_id=? AND read_at IS NULL AND expires_at>?
                """, Long.class, userId, Timestamp.from(now));
    }

    public NotificationPageResponse findPage(long userId, NotificationCursor cursor, int size, Instant now) {
        var parameters = new MapSqlParameterSource().addValue("user", userId)
                .addValue("now", Timestamp.from(now)).addValue("limit", size + 1);
        String cursorPredicate = "";
        if (cursor != null) {
            cursorPredicate = "WHERE (created_at,id) < (:cursorAt,:cursorId)";
            parameters.addValue("cursorAt", Timestamp.from(cursor.createdAt())).addValue("cursorId", cursor.id());
        }
        List<InboxRow> rows = new NamedParameterJdbcTemplate(jdbc).query("""
                WITH own AS MATERIALIZED (
                    SELECT * FROM user_notifications WHERE user_id=:user AND expires_at>:now
                ), boundary AS (SELECT COALESCE(max(id),0) AS read_through FROM own),
                page AS (SELECT * FROM own %s ORDER BY created_at DESC,id DESC LIMIT :limit)
                SELECT boundary.read_through,page.*,
                    EXISTS(SELECT 1 FROM announcements a WHERE a.id=page.announcement_id AND NOT a.admin_deleted
                        AND a.status IN ('ORIGINAL','원공고','CORRECTION','정정공고')
                        AND (a.status IN ('ORIGINAL','원공고') OR a.previous_announcement_id IS NOT NULL)
                        AND NOT EXISTS(SELECT 1 FROM announcements successor
                            WHERE successor.previous_announcement_id=a.id AND NOT successor.admin_deleted)
                        AND EXISTS(SELECT 1 FROM supply_rows s JOIN housing_complexes c ON c.id=s.housing_complex_id
                            WHERE s.announcement_id=a.id AND NOT c.admin_deleted)) AS target_available
                FROM boundary LEFT JOIN page ON true ORDER BY page.created_at DESC,page.id DESC
                """.formatted(cursorPredicate), parameters, (row, number) -> {
                    Item item = null;
                    if (row.getObject("id") != null) {
                        Instant readAt = null;
                        if (row.getTimestamp("read_at") != null) {
                            readAt = row.getTimestamp("read_at").toInstant();
                        }
                        item = new Item(Long.toString(row.getLong("id")), "ANNOUNCEMENT_CREATED",
                                Long.toString(row.getLong("announcement_id")), row.getString("title"),
                                row.getTimestamp("created_at").toInstant(), readAt, row.getBoolean("target_available"));
                    }
                    return new InboxRow(item, row.getLong("read_through"));
                });
        var items = new ArrayList<>(rows.stream().map(InboxRow::item).filter(java.util.Objects::nonNull).toList());
        String nextCursor = null;
        if (items.size() > size) {
            items.removeLast();
            Item last = items.getLast();
            nextCursor = new NotificationCursor(last.createdAt(), Long.parseLong(last.id())).encode();
        }
        return new NotificationPageResponse(items, nextCursor, Long.toString(rows.getFirst().readThrough()));
    }

    public boolean markRead(long userId, long id, Instant now) {
        return jdbc.update("""
                UPDATE user_notifications SET read_at=COALESCE(read_at,?) WHERE user_id=? AND id=? AND expires_at>?
                """, Timestamp.from(now), userId, id, Timestamp.from(now)) == 1;
    }

    public Optional<Long> findOwner(long id) {
        return jdbc.query("SELECT user_id FROM user_notifications WHERE id=?", (row, number) -> row.getLong(1), id)
                .stream().findFirst();
    }

    public long maximumId(long userId, Instant now) {
        return jdbc.queryForObject("SELECT COALESCE(max(id),0) FROM user_notifications WHERE user_id=? AND expires_at>?",
                Long.class, userId, Timestamp.from(now));
    }

    public int markThrough(long userId, long bound, Instant now) {
        return jdbc.update("""
                UPDATE user_notifications SET read_at=? WHERE user_id=? AND id<=? AND read_at IS NULL AND expires_at>?
                """, Timestamp.from(now), userId, bound, Timestamp.from(now));
    }

    private record InboxRow(Item item, long readThrough) {
    }
}

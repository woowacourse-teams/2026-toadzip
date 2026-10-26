package com.toadzip.backend.interest.repository;

import com.toadzip.backend.announcement.repository.ApplicationScheduleSql;
import com.toadzip.backend.interest.domain.BookmarkType;
import com.toadzip.backend.interest.dto.BookmarkResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class BookmarkRepository {
    private final JdbcClient jdbc;

    public List<BookmarkResponse.Item> findForUser(long userId) {
        List<BookmarkResponse.Item> items = new ArrayList<>(complexes(userId));
        items.addAll(announcements(userId));
        items.sort(Comparator.comparing(BookmarkResponse.Item::createdAt).reversed()
                .thenComparing(BookmarkResponse.Item::type).thenComparing(BookmarkResponse.Item::targetId));
        return List.copyOf(items);
    }

    private List<BookmarkResponse.Item> complexes(long userId) {
        return jdbc.sql("""
                SELECT DISTINCT ON (c.id) c.id, c.name, c.road_address, c.admin_deleted, f.created_at
                FROM favorite_housing_complexes f JOIN housing_complexes c ON c.id = f.housing_complex_id
                WHERE f.user_id = :userId ORDER BY c.id, f.created_at DESC, f.id DESC
                """).param("userId", userId).query((row, number) -> new BookmarkResponse.Item(
                        BookmarkType.COMPLEX, row.getString("id"), row.getString("name"),
                        row.getObject("created_at", LocalDateTime.class), !row.getBoolean("admin_deleted"),
                        row.getString("road_address"), null, null, null)).list();
    }

    private List<BookmarkResponse.Item> announcements(long userId) {
        String sql = "SELECT DISTINCT ON (a.id) a.id, a.name, a.admin_deleted, f.created_at, "
                + ApplicationScheduleSql.status("a", null) + " AS recruitment_status, "
                + ApplicationScheduleSql.displayPeriodColumns("a")
                + " FROM favorite_announcements f JOIN announcements a ON a.id = f.announcement_id "
                + ApplicationScheduleSql.displayPeriodJoin("a", null)
                + " WHERE f.user_id = :userId ORDER BY a.id, f.created_at DESC, f.id DESC";
        return jdbc.sql(sql).param("userId", userId).param("today", LocalDate.now(ZoneId.of("Asia/Seoul")))
                .query((row, number) -> new BookmarkResponse.Item(
                        BookmarkType.ANNOUNCEMENT, row.getString("id"), row.getString("name"),
                        row.getObject("created_at", LocalDateTime.class), !row.getBoolean("admin_deleted"),
                        null, row.getString("recruitment_status"),
                        row.getObject("application_start_date", LocalDate.class),
                        row.getObject("application_end_date", LocalDate.class))).list();
    }

    public boolean lockUser(long userId) {
        return jdbc.sql("SELECT id FROM users WHERE id = :id FOR UPDATE")
                .param("id", userId).query(Long.class).optional().isPresent();
    }

    public boolean save(long userId, BookmarkType type, long targetId) {
        String table = targetTable(type);
        String column = targetColumn(type);
        String favorites = favoriteTable(type);
        int inserted = jdbc.sql("INSERT INTO " + favorites + " (user_id, " + column + ", created_at) "
                + "SELECT :userId, target.id, CURRENT_TIMESTAMP FROM " + table + " target "
                + "WHERE target.id = :targetId AND target.admin_deleted = false AND NOT EXISTS "
                + "(SELECT 1 FROM " + favorites + " WHERE user_id = :userId AND " + column + " = :targetId)")
                .param("userId", userId).param("targetId", targetId).update();
        if (inserted > 0) {
            return true;
        }
        return jdbc.sql("SELECT id FROM " + table + " WHERE id = :id AND admin_deleted = false")
                .param("id", targetId).query(Long.class).optional().isPresent();
    }

    public void remove(long userId, BookmarkType type, long targetId) {
        jdbc.sql("DELETE FROM " + favoriteTable(type) + " WHERE user_id = :userId AND "
                + targetColumn(type) + " = :targetId").param("userId", userId).param("targetId", targetId).update();
    }

    private String favoriteTable(BookmarkType type) {
        if (type == BookmarkType.COMPLEX) {
            return "favorite_housing_complexes";
        }
        return "favorite_announcements";
    }

    private String targetTable(BookmarkType type) {
        if (type == BookmarkType.COMPLEX) {
            return "housing_complexes";
        }
        return "announcements";
    }

    private String targetColumn(BookmarkType type) {
        if (type == BookmarkType.COMPLEX) {
            return "housing_complex_id";
        }
        return "announcement_id";
    }
}

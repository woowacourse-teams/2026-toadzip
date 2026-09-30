package com.toadzip.backend.announcement.repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AnnouncementViewRepository {

    private final JdbcClient jdbc;

    public AnnouncementViewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> lockViewCount(long announcementId) {
        return jdbc.sql("""
                SELECT view_count FROM announcements
                WHERE id = :id AND admin_deleted = false
                FOR UPDATE
                """).param("id", announcementId).query(Long.class).optional();
    }

    public boolean recordOncePerDay(long announcementId, UUID viewerId, LocalDate today) {
        return jdbc.sql("""
                INSERT INTO announcement_views (announcement_id, viewer_id, viewed_on)
                VALUES (:id, :viewer, :today)
                ON CONFLICT (announcement_id, viewer_id)
                DO UPDATE SET viewed_on = EXCLUDED.viewed_on
                WHERE announcement_views.viewed_on < EXCLUDED.viewed_on
                """).param("id", announcementId).param("viewer", viewerId).param("today", today).update() == 1;
    }

    public long increment(long announcementId) {
        return jdbc.sql("""
                UPDATE announcements SET view_count = view_count + 1
                WHERE id = :id RETURNING view_count
                """).param("id", announcementId).query(Long.class).single();
    }
}

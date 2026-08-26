package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.Announcement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    @Query("""
            SELECT announcement
            FROM Announcement announcement
            WHERE NOT EXISTS (
                SELECT nextAnnouncement.id
                FROM Announcement nextAnnouncement
                WHERE nextAnnouncement.previousAnnouncement = announcement
            )
            ORDER BY announcement.postedDate DESC, announcement.id DESC
            """)
    List<Announcement> findLatestLeaves(Pageable pageable);

    @Query("""
            SELECT announcement
            FROM Announcement announcement
            WHERE NOT EXISTS (
                SELECT nextAnnouncement.id
                FROM Announcement nextAnnouncement
                WHERE nextAnnouncement.previousAnnouncement = announcement
            )
              AND (
                  announcement.postedDate < :postedDate
                  OR (announcement.postedDate = :postedDate AND announcement.id < :id)
              )
            ORDER BY announcement.postedDate DESC, announcement.id DESC
            """)
    List<Announcement> findLatestLeavesAfter(
            @Param("postedDate") LocalDate postedDate,
            @Param("id") long id,
            Pageable pageable
    );

    @Query("""
            SELECT announcement
            FROM Announcement announcement
            LEFT JOIN FETCH announcement.previousAnnouncement
            WHERE announcement.id = :id
            """)
    Optional<Announcement> findDetailById(@Param("id") long id);
}

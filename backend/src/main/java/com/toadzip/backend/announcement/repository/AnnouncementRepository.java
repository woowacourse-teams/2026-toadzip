package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.Announcement;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    Optional<Announcement> findBySourceAnnouncementIdentifier(String sourceAnnouncementIdentifier);

    boolean existsByOriginalUrl(String originalUrl);

    boolean existsByPreviousAnnouncementAndIdNot(Announcement previousAnnouncement, long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select announcement from Announcement announcement where announcement.id = :id")
    Optional<Announcement> findByIdForUpdate(long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select announcement from Announcement announcement
            where announcement.sourceAnnouncementIdentifier = :identifier
            """)
    Optional<Announcement> findBySourceAnnouncementIdentifierForUpdate(String identifier);

    @Query("""
            SELECT announcement
            FROM Announcement announcement
            WHERE announcement.id = :id
            """)
    Optional<Announcement> findDetailById(@Param("id") long id);
}

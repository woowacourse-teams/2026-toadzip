package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.Announcement;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {
    @Query(value = """
            SELECT c.* FROM announcements c
            WHERE c.admin_deleted = :deleted
              AND (:review = false OR c.source_review_required = true)
              AND (lower(c.name) LIKE :keyword ESCAPE '!'
                  OR lower(c.source_announcement_identifier) = :identifier)
              AND (:provider = '' OR c.provider IN (:provider, :providerLegacy))
              AND (:rental = '' OR c.supply_type IN (:rental, :rentalLegacy))
              AND (:region = '' OR EXISTS (SELECT 1 FROM supply_rows r
                  JOIN housing_complexes h ON h.id = r.housing_complex_id
                  WHERE r.announcement_id = c.id AND (h.province_code = :region
                      OR h.city_county_district_code = :region)))
              AND (:complexId = 0 OR EXISTS (SELECT 1 FROM supply_rows r
                  WHERE r.announcement_id = c.id AND r.housing_complex_id = :complexId))
            ORDER BY c.id DESC
            """, nativeQuery = true)
    org.springframework.data.domain.Page<Announcement> searchAdmin(String keyword, String identifier, String provider, String providerLegacy,
            String rental, String rentalLegacy, String region, boolean deleted, boolean review, long complexId,
            org.springframework.data.domain.Pageable pageable);


    Optional<Announcement> findBySourceAnnouncementIdentifier(String sourceAnnouncementIdentifier);

    boolean existsByOriginalUrl(String originalUrl);

    boolean existsByPreviousAnnouncementAndAdminDeletedFalse(Announcement previousAnnouncement);

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

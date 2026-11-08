package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementReleaseStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {
    String ADMIN_SEARCH_FROM = " FROM announcements c " + """
            WHERE c.admin_deleted = :deleted
              AND (:review = false OR c.source_review_required = true)
              AND (:unconfirmed = false OR c.release_status = 'UNCONFIRMED')
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
            """;

    Optional<Announcement> findBySourceAnnouncementIdentifier(String sourceAnnouncementIdentifier);

    /** 같은 LH 공고 ID의 공고. 휴지통이 아닌 가장 작은 ID가 LH 공고 정제를 이어받는다. */
    List<Announcement> findAllByLhPanIdOrderByIdAsc(String lhPanId);

    List<Announcement> findAllByLhPanIdIsNotNullAndAdminDeletedFalse();

    boolean existsByOriginalUrl(String originalUrl);

    boolean existsByIdAndReleaseStatus(long id, AnnouncementReleaseStatus releaseStatus);

    boolean existsByPreviousAnnouncementAndAdminDeletedFalse(Announcement previousAnnouncement);

    boolean existsByPreviousAnnouncementAndIdNot(Announcement previousAnnouncement, long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select announcement from Announcement announcement where announcement.id = :id")
    Optional<Announcement> findByIdForUpdate(long id);

    @Query("""
            SELECT announcement
            FROM Announcement announcement
            WHERE announcement.id = :id
            """)
    Optional<Announcement> findDetailById(@Param("id") long id);
}

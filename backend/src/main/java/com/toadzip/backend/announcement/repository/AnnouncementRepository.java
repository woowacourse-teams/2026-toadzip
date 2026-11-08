package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementReleaseStatus;
import com.toadzip.backend.housing.domain.AgencyCode;
import java.time.LocalDate;
import java.util.List;
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
            ORDER BY c.created_at DESC NULLS LAST, c.id DESC
            """, nativeQuery = true)
    org.springframework.data.domain.Page<Announcement> searchAdmin(String keyword, String identifier, String provider, String providerLegacy,
            String rental, String rentalLegacy, String region, boolean deleted, boolean review, boolean unconfirmed,
            long complexId, org.springframework.data.domain.Pageable pageable);


    Optional<Announcement> findBySourceAnnouncementIdentifier(String sourceAnnouncementIdentifier);

    /** 같은 LH 공고 ID의 공고. 휴지통이 아닌 가장 작은 ID가 LH 공고 정제를 이어받는다. */
    List<Announcement> findAllByLhPanIdOrderByIdAsc(String lhPanId);

    /** 정정·취소공고가 이을 수 있는 이전 LH 공고 후보. 휴지통 공고와 자신, 늦게 올라온 공고, 이미 휴지통이 아닌 LH 공고가 이은 공고는 뺀다. */
    @Query("""
            SELECT announcement
            FROM Announcement announcement
            WHERE announcement.lhPanId IS NOT NULL
              AND announcement.adminDeleted = false
              AND announcement.id <> :excludedId
              AND announcement.postedDate <= :postedTo
              AND NOT EXISTS (
                  SELECT 1 FROM Announcement successor
                  WHERE successor.previousAnnouncement = announcement
                    AND successor.lhPanId IS NOT NULL AND successor.adminDeleted = false)
            """)
    List<Announcement> findLhLinkableOriginals(@Param("excludedId") Long excludedId,
            @Param("postedTo") LocalDate postedTo);

    /**
     * 정정공고가 이을 수 있는 같은 기관의 이전 공고 후보다. 휴지통 공고와 정정공고 자신은 빼고, 이미 다른 공고(휴지통 공고 포함,
     * 이전 공고는 하나의 후속 공고에만 이어진다)가 이은 공고도 뺀다.
     */
    @Query("""
            SELECT announcement
            FROM Announcement announcement
            WHERE announcement.provider = :provider
              AND announcement.adminDeleted = false
              AND announcement.id <> :excludedId
              AND announcement.postedDate BETWEEN :postedFrom AND :postedTo
              AND NOT EXISTS (
                  SELECT 1 FROM Announcement successor WHERE successor.previousAnnouncement = announcement)
            """)
    List<Announcement> findLinkableOriginals(@Param("provider") AgencyCode provider,
            @Param("excludedId") Long excludedId, @Param("postedFrom") LocalDate postedFrom,
            @Param("postedTo") LocalDate postedTo);

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

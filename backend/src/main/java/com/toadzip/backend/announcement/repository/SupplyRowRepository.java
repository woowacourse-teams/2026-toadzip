package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.housing.domain.HousingType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplyRowRepository extends JpaRepository<SupplyRow, Long> {

    Optional<SupplyRow> findBySourceSupplyRowIdentifier(String sourceSupplyRowIdentifier);

    List<SupplyRow> findAllByAnnouncement(Announcement announcement);

    boolean existsByHousingType(HousingType housingType);

    @Query("""
            SELECT supplyRow
            FROM SupplyRow supplyRow
            LEFT JOIN FETCH supplyRow.housingComplex
            LEFT JOIN FETCH supplyRow.housingType
            WHERE supplyRow.announcement.id IN :announcementIds
            ORDER BY supplyRow.announcement.id ASC, supplyRow.displayOrder ASC, supplyRow.id ASC
            """)
    List<SupplyRow> findAllByAnnouncementIdIn(
            @Param("announcementIds") Collection<Long> announcementIds
    );
}

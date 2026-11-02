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

    java.util.List<SupplyRow> findAllByHousingComplexId(long complexId);

    boolean existsByHousingComplexIdAndAnnouncementAdminDeletedFalse(long complexId);

    Optional<SupplyRow> findBySourceSupplyRowIdentifier(String sourceSupplyRowIdentifier);

    List<SupplyRow> findAllByAnnouncement(Announcement announcement);

    /**
     * 대표 주택형이거나 함께 모집하는 주택형으로 연결된 공급행이 있는지 본다. 휴지통 주택형은 엔티티 조인에서 빠지므로
     * 연결 표를 직접 본다.
     */
    default boolean existsByHousingType(HousingType housingType) {
        return existsByHousingTypeId(housingType.getId());
    }

    @Query(nativeQuery = true, value = """
            SELECT EXISTS (SELECT 1 FROM supply_rows WHERE housing_type_id = :typeId)
                OR EXISTS (SELECT 1 FROM supply_row_housing_types WHERE housing_type_id = :typeId)
            """)
    boolean existsByHousingTypeId(@Param("typeId") long typeId);

    @Query("""
            SELECT supplyRow
            FROM SupplyRow supplyRow
            LEFT JOIN FETCH supplyRow.housingComplex
            LEFT JOIN FETCH supplyRow.housingType
            LEFT JOIN FETCH supplyRow.supplyGroup
            WHERE supplyRow.announcement.id IN :announcementIds
            ORDER BY supplyRow.announcement.id ASC, supplyRow.displayOrder ASC, supplyRow.id ASC
            """)
    List<SupplyRow> findAllByAnnouncementIdIn(
            @Param("announcementIds") Collection<Long> announcementIds
    );
}

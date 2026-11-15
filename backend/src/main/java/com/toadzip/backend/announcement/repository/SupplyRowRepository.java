package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.housing.domain.HousingType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplyRowRepository extends JpaRepository<SupplyRow, Long> {

    Optional<SupplyRow> findBySourceSupplyRowIdentifier(String sourceSupplyRowIdentifier);

    List<SupplyRow> findAllByAnnouncement(Announcement announcement);

    /** 이 주택형에 연결된 공급행이 있는지 본다. 휴지통 주택형은 엔티티 조인에서 빠지므로 열을 직접 본다. */
    default boolean existsByHousingType(HousingType housingType) {
        return existsByHousingTypeId(housingType.getId());
    }

    @Query(nativeQuery = true, value = """
            SELECT EXISTS (SELECT 1 FROM supply_rows WHERE housing_type_id = :typeId)
            """)
    boolean existsByHousingTypeId(@Param("typeId") long typeId);

    @Query("""
            SELECT supplyRow
            FROM SupplyRow supplyRow
            LEFT JOIN FETCH supplyRow.housingComplex
            LEFT JOIN FETCH supplyRow.housingType
            LEFT JOIN FETCH supplyRow.announcementComplex announcementComplex
            LEFT JOIN FETCH announcementComplex.housingComplex
            WHERE supplyRow.announcement.id IN :announcementIds
            ORDER BY supplyRow.announcement.id ASC, supplyRow.displayOrder ASC, supplyRow.id ASC
            """)
    List<SupplyRow> findAllByAnnouncementIdIn(
            @Param("announcementIds") Collection<Long> announcementIds
    );

    /**
     * 관리자 보정이 정한 주택형 수정 표시를 그대로 쓴다. 공급표 쓰기(SupplySheetStore)는 이 표시를 바꾸지 않으므로 보정이 따로 쓴다.
     * 영속성 컨텍스트의 공급행은 낡으므로 부르는 쪽이 다시 읽는다(refresh).
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE SupplyRow supplyRow SET supplyRow.adminModified = :modified WHERE supplyRow.id IN :ids")
    int markAdminModified(@Param("ids") Collection<Long> ids, @Param("modified") boolean modified);
}

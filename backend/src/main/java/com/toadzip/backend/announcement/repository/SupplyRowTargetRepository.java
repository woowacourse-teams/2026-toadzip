package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.SupplyRowTarget;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplyRowTargetRepository extends JpaRepository<SupplyRowTarget, Long> {

    @Query("""
            SELECT cell
            FROM SupplyRowTarget cell
            JOIN FETCH cell.target
            JOIN FETCH cell.supplyRow
            WHERE cell.supplyRow.id IN :supplyRowIds
            ORDER BY cell.supplyRow.id ASC, cell.displayOrder ASC, cell.id ASC
            """)
    List<SupplyRowTarget> findAllBySupplyRowIdIn(@Param("supplyRowIds") Collection<Long> supplyRowIds);

    @Query("""
            SELECT cell
            FROM SupplyRowTarget cell
            JOIN FETCH cell.target
            JOIN FETCH cell.supplyRow row
            WHERE row.announcement.id = :announcementId
            ORDER BY row.id ASC, cell.displayOrder ASC, cell.id ASC
            """)
    List<SupplyRowTarget> findAllByAnnouncementId(@Param("announcementId") Long announcementId);
}

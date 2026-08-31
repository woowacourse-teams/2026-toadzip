package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplyTargetRepository extends JpaRepository<SupplyTarget, Long> {

    @Modifying
    @Query("DELETE FROM SupplyTarget target WHERE target.supplyRow IN :supplyRows")
    int deleteAllBySupplyRowIn(@Param("supplyRows") Collection<SupplyRow> supplyRows);

    List<SupplyTarget> findAllBySupplyRow(SupplyRow supplyRow);

    @Query("""
            SELECT target
            FROM SupplyTarget target
            WHERE target.supplyRow.id IN :supplyRowIds
            ORDER BY target.supplyRow.id ASC, target.displayOrder ASC, target.id ASC
            """)
    List<SupplyTarget> findAllBySupplyRowIdIn(
            @Param("supplyRowIds") Collection<Long> supplyRowIds
    );
}

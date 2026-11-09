package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplyTargetRepository extends JpaRepository<SupplyTarget, Long> {

    @EntityGraph(attributePaths = {"supplyCountGroup", "reserveCountGroup"})
    List<SupplyTarget> findAllBySupplyRow(SupplyRow supplyRow);

    @Query("""
            SELECT target
            FROM SupplyTarget target
            LEFT JOIN FETCH target.supplyCountGroup
            LEFT JOIN FETCH target.reserveCountGroup
            WHERE target.supplyRow.id IN :supplyRowIds
            ORDER BY target.supplyRow.id ASC, target.displayOrder ASC, target.id ASC
            """)
    List<SupplyTarget> findAllBySupplyRowIdIn(
            @Param("supplyRowIds") Collection<Long> supplyRowIds
    );
}

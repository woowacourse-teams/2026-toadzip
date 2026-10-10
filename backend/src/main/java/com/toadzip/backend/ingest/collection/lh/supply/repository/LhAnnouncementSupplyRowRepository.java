package com.toadzip.backend.ingest.collection.lh.supply.repository;

import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplyRow;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface LhAnnouncementSupplyRowRepository extends JpaRepository<LhAnnouncementSupplyRow, Long> {

    List<LhAnnouncementSupplyRow> findAllBySourceIdOrderBySourceOrderAsc(Long sourceId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM LhAnnouncementSupplyRow row WHERE row.source.id = :sourceId")
    void deleteRows(Long sourceId);
}

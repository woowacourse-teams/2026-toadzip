package com.toadzip.backend.ingest.collection.lh.detail.repository;

import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailRow;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface LhAnnouncementDetailRowRepository extends JpaRepository<LhAnnouncementDetailRow, Long> {

    List<LhAnnouncementDetailRow> findAllBySourceIdOrderBySourceOrderAsc(Long sourceId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM LhAnnouncementDetailRow row WHERE row.source.id = :sourceId")
    void deleteRows(Long sourceId);
}

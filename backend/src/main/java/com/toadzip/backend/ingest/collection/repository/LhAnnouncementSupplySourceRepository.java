package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LhAnnouncementSupplySourceRepository extends JpaRepository<LhAnnouncementSupplySource, Long> {

    @Query("select distinct source.panId from LhAnnouncementSupplySource source where source.panId in :panIds")
    List<String> findStoredPanIds(Collection<String> panIds);

    List<LhAnnouncementSupplySource> findAllByPanIdOrderBySourceOrderAsc(String panId);

    void deleteByPanId(String panId);
}

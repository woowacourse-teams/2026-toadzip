package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.LhAnnouncementDetailSource;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LhAnnouncementDetailSourceRepository extends JpaRepository<LhAnnouncementDetailSource, Long> {

    @Query("select distinct source.panId from LhAnnouncementDetailSource source where source.panId in :panIds")
    List<String> findStoredPanIds(Collection<String> panIds);

    List<LhAnnouncementDetailSource> findAllByPanIdOrderBySourceOrderAsc(String panId);

    void deleteByPanId(String panId);
}

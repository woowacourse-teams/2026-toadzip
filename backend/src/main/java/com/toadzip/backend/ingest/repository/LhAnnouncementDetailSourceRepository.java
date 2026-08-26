package com.toadzip.backend.ingest.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.toadzip.backend.ingest.domain.LhAnnouncementDetailSource;

public interface LhAnnouncementDetailSourceRepository extends JpaRepository<LhAnnouncementDetailSource, Long> {

    @Query("select distinct source.panId from LhAnnouncementDetailSource source where source.panId in :panIds")
    List<String> findStoredPanIds(Collection<String> panIds);

    void deleteByPanId(String panId);
}

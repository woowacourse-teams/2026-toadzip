package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LhAnnouncementSupplySourceRepository extends JpaRepository<LhAnnouncementSupplySource, Long> {

    List<LhAnnouncementSupplySource> findAllByPanIdOrderBySourceOrderAsc(String panId);

    void deleteByPanId(String panId);
}

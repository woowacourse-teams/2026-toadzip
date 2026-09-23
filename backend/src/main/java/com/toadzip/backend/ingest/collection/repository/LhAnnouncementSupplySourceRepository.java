package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LhAnnouncementSupplySourceRepository extends JpaRepository<LhAnnouncementSupplySource, Long> {

    boolean existsByPanIdAndRequestHash(String panId, String requestHash);

    List<LhAnnouncementSupplySource> findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
            String panId,
            String requestHash
    );

    void deleteByPanIdAndRequestHash(String panId, String requestHash);
}

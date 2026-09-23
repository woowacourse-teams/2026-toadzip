package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.LhAnnouncementDetailSource;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LhAnnouncementDetailSourceRepository extends JpaRepository<LhAnnouncementDetailSource, Long> {

    List<LhAnnouncementDetailSource> findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
            String panId,
            String requestHash
    );

    void deleteByPanIdAndRequestHash(String panId, String requestHash);
}

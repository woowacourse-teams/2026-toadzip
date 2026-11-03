package com.toadzip.backend.ingest.lh.collection.repository;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.lh.collection.domain.LhAnnouncementQuerySource;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LhAnnouncementQuerySourceRepository extends JpaRepository<LhAnnouncementQuerySource, Long> {

    Optional<LhAnnouncementQuerySource> findBySourceAndQueryHash(CollectionSource source, String queryHash);

    Optional<LhAnnouncementQuerySource> findBySourceAndPanIdAndRequestHash(
            CollectionSource source, String panId, String requestHash);
}

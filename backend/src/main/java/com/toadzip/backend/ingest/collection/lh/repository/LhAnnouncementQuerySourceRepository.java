package com.toadzip.backend.ingest.collection.lh.repository;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuerySource;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LhAnnouncementQuerySourceRepository extends JpaRepository<LhAnnouncementQuerySource, Long> {

    Optional<LhAnnouncementQuerySource> findBySourceAndQueryHash(CollectionSource source, String queryHash);

    List<LhAnnouncementQuerySource> findAllBySourceAndPanIdIn(CollectionSource source, Collection<String> panIds);

    Optional<LhAnnouncementQuerySource> findBySourceAndPanIdAndRequestHash(
            CollectionSource source, String panId, String requestHash);
}

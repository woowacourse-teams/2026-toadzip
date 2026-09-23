package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionLink;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LhAnnouncementCollectionLinkRepository
        extends JpaRepository<LhAnnouncementCollectionLink, Long> {

    List<LhAnnouncementCollectionLink> findAllBySourceAndSourceAnnouncementKeyIn(
            ExternalDataSource source,
            Collection<String> sourceAnnouncementKeys
    );

    Optional<LhAnnouncementCollectionLink> findBySourceAndSourceAnnouncementKey(
            ExternalDataSource source,
            String sourceAnnouncementKey
    );
}

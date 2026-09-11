package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionLink;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LhAnnouncementCollectionLinkRepository
        extends JpaRepository<LhAnnouncementCollectionLink, Long> {

    @Query("""
            select link.sourceAnnouncementKey
            from LhAnnouncementCollectionLink link
            where link.source = :source and link.sourceAnnouncementKey in :sourceAnnouncementKeys
            """)
    List<String> findLinkedSourceAnnouncementKeys(
            @Param("source") ExternalDataSource source,
            @Param("sourceAnnouncementKeys") Collection<String> sourceAnnouncementKeys
    );

    Optional<LhAnnouncementCollectionLink> findBySourceAndSourceAnnouncementKey(
            ExternalDataSource source,
            String sourceAnnouncementKey
    );
}

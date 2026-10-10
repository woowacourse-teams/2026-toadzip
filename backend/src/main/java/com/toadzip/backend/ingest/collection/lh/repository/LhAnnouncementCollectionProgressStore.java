package com.toadzip.backend.ingest.collection.lh.repository;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementCollectionLink;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LhAnnouncementCollectionProgressStore {

    private final LhAnnouncementCollectionLinkRepository linkRepository;
    private final Clock clock;

    public LhAnnouncementCollectionProgressStore(
            LhAnnouncementCollectionLinkRepository linkRepository,
            Clock clock
    ) {
        this.linkRepository = linkRepository;
        this.clock = clock;
    }

    @Transactional
    public void complete(
            ExternalDataSource source,
            String sourceAnnouncementKey,
            String requestDescription,
            String panId
    ) {
        saveLink(source, sourceAnnouncementKey, requestDescription, panId, clock.instant());
    }

    @Transactional
    public void link(
            ExternalDataSource source,
            String sourceAnnouncementKey,
            String requestDescription,
            String panId
    ) {
        saveLink(source, sourceAnnouncementKey, requestDescription, panId, clock.instant());
    }

    private void saveLink(
            ExternalDataSource source,
            String sourceAnnouncementKey,
            String requestDescription,
            String panId,
            Instant completedAt
    ) {
        LhAnnouncementCollectionLink link = linkRepository
                .findBySourceAndSourceAnnouncementKey(source, sourceAnnouncementKey)
                .orElseGet(() -> LhAnnouncementCollectionLink.complete(
                        source,
                        sourceAnnouncementKey,
                        requestDescription,
                        panId,
                        completedAt
                ));
        if (link.getId() != null) {
            link.updateFrom(requestDescription, panId, completedAt);
        }
        linkRepository.save(link);
    }

}

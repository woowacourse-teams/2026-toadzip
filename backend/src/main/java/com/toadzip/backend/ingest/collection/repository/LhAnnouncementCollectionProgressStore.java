package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionCheckpoint;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionLink;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LhAnnouncementCollectionProgressStore {

    private final LhAnnouncementCollectionCheckpointRepository checkpointRepository;
    private final LhAnnouncementDetailSourceRepository detailRepository;
    private final LhAnnouncementSupplySourceRepository supplyRepository;
    private final LhAnnouncementCollectionLinkRepository linkRepository;
    private final Clock clock;

    public LhAnnouncementCollectionProgressStore(
            LhAnnouncementCollectionCheckpointRepository checkpointRepository,
            LhAnnouncementDetailSourceRepository detailRepository,
            LhAnnouncementSupplySourceRepository supplyRepository,
            LhAnnouncementCollectionLinkRepository linkRepository,
            Clock clock
    ) {
        this.checkpointRepository = checkpointRepository;
        this.detailRepository = detailRepository;
        this.supplyRepository = supplyRepository;
        this.linkRepository = linkRepository;
        this.clock = clock;
    }

    public BatchProgress findBatch(
            ExternalDataSource source,
            Collection<String> requestDescriptions,
            Collection<String> panIds,
            Collection<String> sourceAnnouncementKeys,
            Instant freshCompletedAfter
    ) {
        if (requestDescriptions.isEmpty()) {
            return BatchProgress.empty();
        }
        Set<String> requestHashes = requestDescriptions.stream()
                .map(LhAnnouncementCollectionCheckpoint::requestHashOf)
                .collect(Collectors.toSet());
        Set<String> freshRequestHashes = Set.copyOf(
                checkpointRepository.findFreshRequestHashes(
                        source,
                        requestHashes,
                        freshCompletedAfter
                )
        );
        Set<String> storedPanIds = findStoredPanIds(source, panIds);
        Set<String> historyPanIds = Set.copyOf(checkpointRepository.findHistoryPanIds(source, panIds));
        Map<String, String> linkedRequestHashes = findLinkedRequestHashes(
                source,
                sourceAnnouncementKeys
        );
        return new BatchProgress(
                freshRequestHashes,
                storedPanIds,
                historyPanIds,
                linkedRequestHashes
        );
    }

    @Transactional
    public void complete(
            ExternalDataSource source,
            String sourceAnnouncementKey,
            String requestDescription,
            String panId
    ) {
        LhAnnouncementCollectionCheckpoint checkpoint = LhAnnouncementCollectionCheckpoint.complete(
                source,
                sourceAnnouncementKey,
                requestDescription,
                panId,
                clock.instant()
        );
        checkpointRepository.upsert(
                checkpoint.getSource().name(),
                checkpoint.getSourceAnnouncementKey(),
                checkpoint.getRequestHash(),
                checkpoint.getRequestDescription(),
                checkpoint.getPanId(),
                checkpoint.getCompletedAt()
        );
        saveLink(source, sourceAnnouncementKey, requestDescription, panId, checkpoint.getCompletedAt());
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

    private Set<String> findStoredPanIds(ExternalDataSource source, Collection<String> panIds) {
        if (source == ExternalDataSource.LH_ANNOUNCEMENT_DETAIL) {
            return Set.copyOf(detailRepository.findStoredPanIds(panIds));
        }
        if (source == ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY) {
            return Set.copyOf(supplyRepository.findStoredPanIds(panIds));
        }
        throw new IllegalArgumentException("LH 공고 상세·공급 원천만 확인할 수 있습니다.");
    }

    private Map<String, String> findLinkedRequestHashes(
            ExternalDataSource source,
            Collection<String> sourceAnnouncementKeys
    ) {
        if (sourceAnnouncementKeys.isEmpty()) {
            return Map.of();
        }
        return linkRepository.findAllBySourceAndSourceAnnouncementKeyIn(source, sourceAnnouncementKeys)
                .stream()
                .collect(Collectors.toUnmodifiableMap(
                        LhAnnouncementCollectionLink::getSourceAnnouncementKey,
                        LhAnnouncementCollectionLink::getRequestHash
                ));
    }

    public record BatchProgress(
            Set<String> freshRequestHashes,
            Set<String> storedPanIds,
            Set<String> historyPanIds,
            Map<String, String> linkedRequestHashes
    ) {

        public BatchProgress(
                Set<String> freshRequestHashes,
                Set<String> storedPanIds,
                Set<String> historyPanIds
        ) {
            this(freshRequestHashes, storedPanIds, historyPanIds, Map.of());
        }

        public BatchProgress {
            freshRequestHashes = Set.copyOf(freshRequestHashes);
            storedPanIds = Set.copyOf(storedPanIds);
            historyPanIds = Set.copyOf(historyPanIds);
            linkedRequestHashes = Map.copyOf(linkedRequestHashes);
        }

        public static BatchProgress empty() {
            return new BatchProgress(Set.of(), Set.of(), Set.of());
        }

        public boolean isFresh(String requestDescription) {
            return freshRequestHashes.contains(
                    LhAnnouncementCollectionCheckpoint.requestHashOf(requestDescription)
            );
        }

        public boolean isLinkedTo(String sourceAnnouncementKey, String requestDescription) {
            String requestHash = LhAnnouncementCollectionCheckpoint.requestHashOf(requestDescription);
            return requestHash.equals(linkedRequestHashes.get(sourceAnnouncementKey));
        }
    }
}

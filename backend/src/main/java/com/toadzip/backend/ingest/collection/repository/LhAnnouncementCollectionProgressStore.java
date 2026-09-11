package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionCheckpoint;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionLink;
import java.time.Clock;
import java.util.Collection;
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
            Collection<String> panIds
    ) {
        return findBatch(source, requestDescriptions, panIds, Set.of());
    }

    public BatchProgress findBatch(
            ExternalDataSource source,
            Collection<String> requestDescriptions,
            Collection<String> panIds,
            Collection<String> sourceAnnouncementKeys
    ) {
        if (requestDescriptions.isEmpty()) {
            return BatchProgress.empty();
        }
        Set<String> requestHashes = requestDescriptions.stream()
                .map(LhAnnouncementCollectionCheckpoint::requestHashOf)
                .collect(Collectors.toSet());
        Set<String> completedRequestHashes = Set.copyOf(
                checkpointRepository.findCompletedRequestHashes(source, requestHashes)
        );
        Set<String> storedPanIds = findStoredPanIds(source, panIds);
        Set<String> historyPanIds = Set.copyOf(checkpointRepository.findHistoryPanIds(source, panIds));
        Set<String> linkedSourceAnnouncementKeys = findLinkedSourceAnnouncementKeys(
                source,
                sourceAnnouncementKeys
        );
        return new BatchProgress(
                completedRequestHashes,
                storedPanIds,
                historyPanIds,
                linkedSourceAnnouncementKeys
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
        checkpointRepository.insertIfAbsent(
                checkpoint.getSource().name(),
                checkpoint.getSourceAnnouncementKey(),
                checkpoint.getRequestHash(),
                checkpoint.getRequestDescription(),
                checkpoint.getPanId(),
                checkpoint.getCompletedAt()
        );
        LhAnnouncementCollectionLink link = linkRepository
                .findBySourceAndSourceAnnouncementKey(source, sourceAnnouncementKey)
                .orElseGet(() -> LhAnnouncementCollectionLink.complete(
                        source,
                        sourceAnnouncementKey,
                        requestDescription,
                        panId,
                        checkpoint.getCompletedAt()
                ));
        if (link.getId() != null) {
            link.updateFrom(requestDescription, panId, checkpoint.getCompletedAt());
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

    private Set<String> findLinkedSourceAnnouncementKeys(
            ExternalDataSource source,
            Collection<String> sourceAnnouncementKeys
    ) {
        if (sourceAnnouncementKeys.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(linkRepository.findLinkedSourceAnnouncementKeys(source, sourceAnnouncementKeys));
    }

    public record BatchProgress(
            Set<String> completedRequestHashes,
            Set<String> storedPanIds,
            Set<String> historyPanIds,
            Set<String> linkedSourceAnnouncementKeys
    ) {

        public BatchProgress(
                Set<String> completedRequestHashes,
                Set<String> storedPanIds,
                Set<String> historyPanIds
        ) {
            this(completedRequestHashes, storedPanIds, historyPanIds, Set.of());
        }

        public BatchProgress {
            completedRequestHashes = Set.copyOf(completedRequestHashes);
            storedPanIds = Set.copyOf(storedPanIds);
            historyPanIds = Set.copyOf(historyPanIds);
            linkedSourceAnnouncementKeys = Set.copyOf(linkedSourceAnnouncementKeys);
        }

        public static BatchProgress empty() {
            return new BatchProgress(Set.of(), Set.of(), Set.of());
        }

        public boolean isCompleted(String requestDescription) {
            return completedRequestHashes.contains(
                    LhAnnouncementCollectionCheckpoint.requestHashOf(requestDescription)
            );
        }

        public boolean isLinked(String sourceAnnouncementKey) {
            return linkedSourceAnnouncementKeys.contains(sourceAnnouncementKey);
        }
    }
}

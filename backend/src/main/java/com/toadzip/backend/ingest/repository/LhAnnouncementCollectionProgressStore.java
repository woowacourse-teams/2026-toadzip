package com.toadzip.backend.ingest.repository;

import java.time.Clock;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.domain.LhAnnouncementCollectionCheckpoint;

@Repository
public class LhAnnouncementCollectionProgressStore {

    private final LhAnnouncementCollectionCheckpointRepository checkpointRepository;
    private final LhAnnouncementDetailSourceRepository detailRepository;
    private final LhAnnouncementSupplySourceRepository supplyRepository;
    private final Clock clock;

    public LhAnnouncementCollectionProgressStore(
            LhAnnouncementCollectionCheckpointRepository checkpointRepository,
            LhAnnouncementDetailSourceRepository detailRepository,
            LhAnnouncementSupplySourceRepository supplyRepository,
            Clock clock
    ) {
        this.checkpointRepository = checkpointRepository;
        this.detailRepository = detailRepository;
        this.supplyRepository = supplyRepository;
        this.clock = clock;
    }

    public BatchProgress findBatch(
            ExternalDataSource source,
            Collection<String> requestDescriptions,
            Collection<String> panIds
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
        return new BatchProgress(completedRequestHashes, storedPanIds, historyPanIds);
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

    public record BatchProgress(
            Set<String> completedRequestHashes,
            Set<String> storedPanIds,
            Set<String> historyPanIds
    ) {

        public BatchProgress {
            completedRequestHashes = Set.copyOf(completedRequestHashes);
            storedPanIds = Set.copyOf(storedPanIds);
            historyPanIds = Set.copyOf(historyPanIds);
        }

        public static BatchProgress empty() {
            return new BatchProgress(Set.of(), Set.of(), Set.of());
        }

        public boolean isCompleted(String requestDescription) {
            return completedRequestHashes.contains(
                    LhAnnouncementCollectionCheckpoint.requestHashOf(requestDescription)
            );
        }
    }
}

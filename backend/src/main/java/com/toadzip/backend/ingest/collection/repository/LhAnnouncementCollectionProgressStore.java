package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionCheckpoint;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionLink;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LhAnnouncementCollectionProgressStore {

    private final LhAnnouncementCollectionCheckpointRepository checkpointRepository;
    private final LhAnnouncementCollectionLinkRepository linkRepository;
    private final Clock clock;

    public LhAnnouncementCollectionProgressStore(
            LhAnnouncementCollectionCheckpointRepository checkpointRepository,
            LhAnnouncementCollectionLinkRepository linkRepository,
            Clock clock
    ) {
        this.checkpointRepository = checkpointRepository;
        this.linkRepository = linkRepository;
        this.clock = clock;
    }

    public BatchProgress findBatch(
            ExternalDataSource source,
            Collection<String> requestDescriptions,
            Collection<String> sourceAnnouncementKeys,
            Instant freshCompletedAfter
    ) {
        return findBatch(source, requestDescriptions, sourceAnnouncementKeys, freshCompletedAfter, Map.of());
    }

    public BatchProgress findBatch(
            ExternalDataSource source,
            Collection<String> requestDescriptions,
            Collection<String> sourceAnnouncementKeys,
            Instant freshCompletedAfter,
            Map<String, Instant> catalogChangedAtByRequest
    ) {
        if (requestDescriptions.isEmpty()) {
            return BatchProgress.empty();
        }
        Set<String> requestHashes = requestDescriptions.stream()
                .map(LhAnnouncementCollectionCheckpoint::requestHashOf)
                .collect(Collectors.toSet());
        Map<String, Instant> changedAtByHash = new HashMap<>();
        catalogChangedAtByRequest.forEach((request, changedAt) -> changedAtByHash.put(
                LhAnnouncementCollectionCheckpoint.requestHashOf(request), changedAt
        ));
        Set<String> freshRequestHashes = checkpointRepository.findAllBySourceAndRequestHashIn(source, requestHashes)
                .stream()
                .filter(checkpoint -> checkpoint.getCompletedAt().isAfter(freshCompletedAfter))
                .filter(checkpoint -> !changedAtByHash.containsKey(checkpoint.getRequestHash())
                        || !checkpoint.getCompletedAt().isBefore(changedAtByHash.get(checkpoint.getRequestHash())))
                .map(LhAnnouncementCollectionCheckpoint::getRequestHash)
                .collect(Collectors.toUnmodifiableSet());
        Map<String, String> linkedRequestHashes = findLinkedRequestHashes(
                source,
                sourceAnnouncementKeys
        );
        return new BatchProgress(freshRequestHashes, linkedRequestHashes);
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
            Map<String, String> linkedRequestHashes
    ) {

        public BatchProgress {
            freshRequestHashes = Set.copyOf(freshRequestHashes);
            linkedRequestHashes = Map.copyOf(linkedRequestHashes);
        }

        public static BatchProgress empty() {
            return new BatchProgress(Set.of(), Map.of());
        }

        public BatchProgress plus(BatchProgress other) {
            Set<String> mergedFreshRequestHashes = new HashSet<>(freshRequestHashes);
            mergedFreshRequestHashes.addAll(other.freshRequestHashes);
            Map<String, String> mergedLinkedRequestHashes = new HashMap<>(linkedRequestHashes);
            mergedLinkedRequestHashes.putAll(other.linkedRequestHashes);
            return new BatchProgress(mergedFreshRequestHashes, mergedLinkedRequestHashes);
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

package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionCheckpoint;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.domain.LhCatalogSource;
import com.toadzip.backend.ingest.collection.domain.LhCatalogSourceSnapshot;
import com.toadzip.backend.ingest.collection.domain.LhSupplySnapshot;
import com.toadzip.backend.ingest.exception.exception.EmptyLhDetailReplacementException;
import com.toadzip.backend.ingest.exception.exception.EmptyLhSupplyReplacementException;
import com.toadzip.backend.ingest.exception.exception.IncompleteLhSupplyReplacementException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LhSourceStore {

    private final LhCatalogSourceRepository catalogRepository;

    private final LhAnnouncementDetailSourceRepository detailRepository;

    private final LhAnnouncementSupplySourceRepository supplyRepository;

    private final LhAnnouncementCollectionCheckpointRepository checkpointRepository;

    private final Clock clock;

    public LhSourceStore(
            LhCatalogSourceRepository catalogRepository,
            LhAnnouncementDetailSourceRepository detailRepository,
            LhAnnouncementSupplySourceRepository supplyRepository,
            LhAnnouncementCollectionCheckpointRepository checkpointRepository,
            Clock clock
    ) {
        this.catalogRepository = catalogRepository;
        this.detailRepository = detailRepository;
        this.supplyRepository = supplyRepository;
        this.checkpointRepository = checkpointRepository;
        this.clock = clock;
    }

    @Transactional
    public int replaceCatalog(List<LhCatalogSourceSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            throw new IllegalArgumentException("LH 카탈로그 원천 행이 비어 있습니다.");
        }
        List<LhCatalogSource> sources = new ArrayList<>();
        Instant collectedAt = clock.instant();
        for (int sourceOrder = 0; sourceOrder < snapshots.size(); sourceOrder++) {
            LhCatalogSource source = new LhCatalogSource(sourceOrder, snapshots.get(sourceOrder));
            source.markCollectedAt(collectedAt);
            sources.add(source);
        }
        catalogRepository.deleteAllInBatch();
        catalogRepository.saveAll(sources);
        return sources.size();
    }

    @Transactional
    public int replaceDetails(String panId, String requestDescription, List<LhAnnouncementDetailSource> sources) {
        Instant collectedAt = clock.instant();
        String requestHash = LhAnnouncementCollectionCheckpoint.requestHashOf(requestDescription);
        if (sources.isEmpty() && !previousDetails(panId, requestDescription, requestHash).isEmpty()) {
            throw new EmptyLhDetailReplacementException();
        }
        sources.forEach(source -> {
            requirePanId(panId, source.getPanId());
            source.assignRequestHash(requestHash);
            source.markCollectedAt(collectedAt);
        });
        detailRepository.deleteByPanIdAndRequestHash(panId, requestHash);
        detailRepository.saveAll(sources);
        return sources.size();
    }

    @Transactional
    public int replaceSupplies(String panId, String requestDescription, List<LhAnnouncementSupplySource> sources) {
        Instant collectedAt = clock.instant();
        String requestHash = LhAnnouncementCollectionCheckpoint.requestHashOf(requestDescription);
        sources.forEach(source -> requirePanId(panId, source.getPanId()));
        requireCompleteSupplies(panId, requestDescription, requestHash, sources);
        sources.forEach(source -> {
            source.assignRequestHash(requestHash);
            source.markCollectedAt(collectedAt);
        });
        supplyRepository.deleteByPanIdAndRequestHash(panId, requestHash);
        supplyRepository.saveAll(sources);
        return sources.size();
    }

    private void requireCompleteSupplies(
            String panId,
            String requestDescription,
            String requestHash,
            List<LhAnnouncementSupplySource> sources
    ) {
        List<LhAnnouncementSupplySource> previous = previousSupplies(panId, requestDescription, requestHash);
        if (sources.isEmpty() && !previous.isEmpty()) {
            throw new EmptyLhSupplyReplacementException();
        }
        long missingRowCount = LhSupplySnapshot.missingRowCount(previous, sources);
        if (missingRowCount > 0) {
            throw new IncompleteLhSupplyReplacementException(missingRowCount);
        }
    }

    private List<LhAnnouncementDetailSource> previousDetails(
            String panId,
            String requestDescription,
            String requestHash
    ) {
        List<LhAnnouncementDetailSource> current = detailRepository
                .findAllByPanIdAndRequestHashOrderBySourceOrderAsc(panId, requestHash);
        if (!current.isEmpty()) {
            return current;
        }
        return checkpointRepository.findAllBySourceAndPanIdOrderByCompletedAtDesc(
                        ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, panId).stream()
                .filter(checkpoint -> checkpoint.hasSameQuery(requestDescription))
                .map(checkpoint -> detailRepository.findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
                        panId, checkpoint.getRequestHash()))
                .filter(previous -> !previous.isEmpty())
                .findFirst()
                .orElseGet(List::of);
    }

    private List<LhAnnouncementSupplySource> previousSupplies(
            String panId,
            String requestDescription,
            String requestHash
    ) {
        List<LhAnnouncementSupplySource> current = supplyRepository
                .findAllByPanIdAndRequestHashOrderBySourceOrderAsc(panId, requestHash);
        if (!current.isEmpty()) {
            return current;
        }
        return checkpointRepository.findAllBySourceAndPanIdOrderByCompletedAtDesc(
                        ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, panId).stream()
                .filter(checkpoint -> checkpoint.hasSameQuery(requestDescription))
                .map(checkpoint -> supplyRepository.findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
                        panId, checkpoint.getRequestHash()))
                .filter(previous -> !previous.isEmpty())
                .findFirst()
                .orElseGet(List::of);
    }

    private void requirePanId(String requestedPanId, String sourcePanId) {
        if (!requestedPanId.equals(sourcePanId)) {
            throw new IllegalArgumentException("LH 원천 행의 공고 식별자가 조회 조건과 다릅니다.");
        }
    }
}

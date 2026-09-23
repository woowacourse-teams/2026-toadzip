package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionCheckpoint;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.domain.LhCatalogSource;
import com.toadzip.backend.ingest.collection.domain.LhCatalogSourceSnapshot;
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

    private final Clock clock;

    public LhSourceStore(
            LhCatalogSourceRepository catalogRepository,
            LhAnnouncementDetailSourceRepository detailRepository,
            LhAnnouncementSupplySourceRepository supplyRepository,
            Clock clock
    ) {
        this.catalogRepository = catalogRepository;
        this.detailRepository = detailRepository;
        this.supplyRepository = supplyRepository;
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
        sources.forEach(source -> {
            requirePanId(panId, source.getPanId());
            source.assignRequestHash(requestHash);
            source.markCollectedAt(collectedAt);
        });
        detailRepository.deleteByPanIdAndRequestHash(panId, requestHash);
        detailRepository.flush();
        detailRepository.saveAll(sources);
        return sources.size();
    }

    @Transactional
    public int replaceSupplies(String panId, String requestDescription, List<LhAnnouncementSupplySource> sources) {
        Instant collectedAt = clock.instant();
        String requestHash = LhAnnouncementCollectionCheckpoint.requestHashOf(requestDescription);
        sources.forEach(source -> {
            requirePanId(panId, source.getPanId());
            source.assignRequestHash(requestHash);
            source.markCollectedAt(collectedAt);
        });
        supplyRepository.deleteByPanIdAndRequestHash(panId, requestHash);
        supplyRepository.flush();
        supplyRepository.saveAll(sources);
        return sources.size();
    }

    private void requirePanId(String requestedPanId, String sourcePanId) {
        if (!requestedPanId.equals(sourcePanId)) {
            throw new IllegalArgumentException("LH 원천 행의 공고 식별자가 조회 조건과 다릅니다.");
        }
    }
}

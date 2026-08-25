package com.toadzip.backend.ingest.repository;

import java.util.ArrayList;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.toadzip.backend.ingest.domain.LhCatalogSource;
import com.toadzip.backend.ingest.domain.LhNoticeDetailSource;
import com.toadzip.backend.ingest.domain.LhNoticeSupplySource;
import com.toadzip.backend.ingest.dto.LhCatalogSourceItem;

@Repository
public class LhSourceStore {

    private final LhCatalogSourceRepository catalogRepository;

    private final LhNoticeDetailSourceRepository detailRepository;

    private final LhNoticeSupplySourceRepository supplyRepository;

    private final Clock clock;

    public LhSourceStore(
            LhCatalogSourceRepository catalogRepository,
            LhNoticeDetailSourceRepository detailRepository,
            LhNoticeSupplySourceRepository supplyRepository,
            Clock clock
    ) {
        this.catalogRepository = catalogRepository;
        this.detailRepository = detailRepository;
        this.supplyRepository = supplyRepository;
        this.clock = clock;
    }

    @Transactional
    public int replaceCatalog(List<LhCatalogSourceItem> items) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("LH 카탈로그 원천 행이 비어 있습니다.");
        }
        List<LhCatalogSource> sources = new ArrayList<>();
        Instant collectedAt = clock.instant();
        for (int sourceOrder = 0; sourceOrder < items.size(); sourceOrder++) {
            LhCatalogSource source = new LhCatalogSource(sourceOrder, items.get(sourceOrder));
            source.markCollectedAt(collectedAt);
            sources.add(source);
        }
        catalogRepository.deleteAllInBatch();
        catalogRepository.saveAll(sources);
        return sources.size();
    }

    @Transactional
    public int replaceDetails(String panId, List<LhNoticeDetailSource> sources) {
        Instant collectedAt = clock.instant();
        sources.forEach(source -> source.markCollectedAt(collectedAt));
        detailRepository.deleteByPanId(panId);
        detailRepository.flush();
        detailRepository.saveAll(sources);
        return sources.size();
    }

    @Transactional
    public int replaceSupplies(String panId, List<LhNoticeSupplySource> sources) {
        Instant collectedAt = clock.instant();
        sources.forEach(source -> source.markCollectedAt(collectedAt));
        supplyRepository.deleteByPanId(panId);
        supplyRepository.flush();
        supplyRepository.saveAll(sources);
        return sources.size();
    }
}

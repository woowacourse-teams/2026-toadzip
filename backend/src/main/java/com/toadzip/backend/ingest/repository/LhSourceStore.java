package com.toadzip.backend.ingest.repository;

import java.util.ArrayList;
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

    public LhSourceStore(
            LhCatalogSourceRepository catalogRepository,
            LhNoticeDetailSourceRepository detailRepository,
            LhNoticeSupplySourceRepository supplyRepository
    ) {
        this.catalogRepository = catalogRepository;
        this.detailRepository = detailRepository;
        this.supplyRepository = supplyRepository;
    }

    @Transactional
    public int replaceCatalog(List<LhCatalogSourceItem> items) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("LH 카탈로그 원천 행이 비어 있습니다.");
        }
        List<LhCatalogSource> sources = new ArrayList<>();
        for (int sourceOrder = 0; sourceOrder < items.size(); sourceOrder++) {
            sources.add(new LhCatalogSource(sourceOrder, items.get(sourceOrder)));
        }
        catalogRepository.deleteAllInBatch();
        catalogRepository.saveAll(sources);
        return sources.size();
    }

    @Transactional
    public int replaceDetails(String panId, List<LhNoticeDetailSource> sources) {
        detailRepository.deleteByPanId(panId);
        detailRepository.flush();
        detailRepository.saveAll(sources);
        return sources.size();
    }

    @Transactional
    public int replaceSupplies(String panId, List<LhNoticeSupplySource> sources) {
        supplyRepository.deleteByPanId(panId);
        supplyRepository.flush();
        supplyRepository.saveAll(sources);
        return sources.size();
    }
}

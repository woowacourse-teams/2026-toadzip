package com.toadzip.backend.ingest.collection.lh.leasecatalog.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.dto.LhLeaseCatalogCollectedResponse;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.repository.LhLeaseCatalogApiRepository;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import com.toadzip.backend.ingest.collection.paging.service.PagedSource;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LhLeaseCatalogCollector implements PagedSource<
        LhLeaseCatalogCollectionRequest, SourcePage<LhCatalogSourceSnapshot>, LhLeaseCatalogCollectedResponse> {

    private final LhLeaseCatalogApiRepository apiRepository;
    private final LhLeaseCatalogStorageService storage;

    @Override
    public ExternalDataSource source() {
        return ExternalDataSource.LH_LEASE_CATALOG;
    }

    @Override
    public SourcePage<LhCatalogSourceSnapshot> fetch(LhLeaseCatalogCollectionRequest request, int page) {
        return apiRepository.fetch(request, page);
    }

    @Override
    public LhLeaseCatalogCollectionBuffer newBuffer(LhLeaseCatalogCollectionRequest request) {
        return new LhLeaseCatalogCollectionBuffer();
    }

    @Override
    public void store(UUID recordId, LhLeaseCatalogCollectionRequest request, LhLeaseCatalogCollectedResponse collected) {
        storage.complete(recordId, request, collected);
    }

    @Override
    public String failureLogMessage() {
        return "LH 임대 카탈로그 수집 실패";
    }
}

package com.toadzip.backend.ingest.lh.collection.leasecatalog.service;

import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.lh.collection.leasecatalog.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.lh.collection.leasecatalog.dto.api.LhLeaseCatalogCollectionCommand;
import com.toadzip.backend.ingest.collection.paging.service.PagedSourceCollector;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LhLeaseCatalogCollectionService {

    private final LhLeaseCatalogCollector collector;
    private final PagedSourceCollector pagedCollector;

    public ExternalDataCollectionReport collect(LhLeaseCatalogCollectionCommand request) {
        IngestExecutionScope.requireHeld();
        IngestExecutionScope.beginWork("LH 임대 카탈로그", "페이지", -1);
        return pagedCollector.collectAndReport(collector, startedAt -> new LhLeaseCatalogCollectionRequest(
                IngestExecutionScope.currentExecutionId().orElse(null), request.pageSize(), request.maxPages(),
                startedAt));
    }
}

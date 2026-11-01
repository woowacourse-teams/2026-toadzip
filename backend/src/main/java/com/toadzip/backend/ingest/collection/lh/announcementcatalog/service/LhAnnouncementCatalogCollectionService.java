package com.toadzip.backend.ingest.collection.lh.announcementcatalog.service;

import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.dto.LhAnnouncementCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.paging.service.PagedSourceCollector;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LhAnnouncementCatalogCollectionService {

    private static final int PAGE_SIZE = 500;
    private static final int MAX_PAGES = 1_000;

    private final LhAnnouncementCatalogCollector collector;
    private final PagedSourceCollector pagedCollector;

    public ExternalDataCollectionReport collect() {
        IngestExecutionScope.requireHeld();
        IngestExecutionScope.beginWork("LH 공고 목록", "페이지", -1);
        return pagedCollector.collectAndReport(collector, startedAt -> new LhAnnouncementCatalogCollectionRequest(
                IngestExecutionScope.currentExecutionId().orElse(null), PAGE_SIZE, MAX_PAGES, startedAt));
    }
}

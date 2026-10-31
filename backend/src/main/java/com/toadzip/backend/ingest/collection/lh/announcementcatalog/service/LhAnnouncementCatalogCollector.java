package com.toadzip.backend.ingest.collection.lh.announcementcatalog.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogPage;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.dto.LhAnnouncementCatalogCollectedResponse;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.dto.LhAnnouncementCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository.LhAnnouncementCatalogApiRepository;
import com.toadzip.backend.ingest.collection.paging.service.PagedSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LhAnnouncementCatalogCollector implements PagedSource<
        LhAnnouncementCatalogCollectionRequest, LhAnnouncementCatalogPage, LhAnnouncementCatalogCollectedResponse> {

    private final LhAnnouncementCatalogApiRepository apiRepository;
    private final LhAnnouncementCatalogStorageService storage;
    private final MeterRegistry meterRegistry;

    @Override
    public ExternalDataSource source() {
        return ExternalDataSource.LH_ANNOUNCEMENT_CATALOG;
    }

    @Override
    public LhAnnouncementCatalogPage fetch(LhAnnouncementCatalogCollectionRequest request, int page) {
        return apiRepository.fetch(request, page);
    }

    @Override
    public LhAnnouncementCatalogCollectionBuffer newBuffer(LhAnnouncementCatalogCollectionRequest request) {
        return new LhAnnouncementCatalogCollectionBuffer();
    }

    @Override
    public void store(
            UUID recordId, LhAnnouncementCatalogCollectionRequest request,
            LhAnnouncementCatalogCollectedResponse collected
    ) {
        var result = storage.complete(recordId, request, collected);
        meterRegistry.counter("ingest.announcement.catalog.rows", "change", "new").increment(result.newRowCount());
        meterRegistry.counter("ingest.announcement.catalog.rows", "change", "changed")
                .increment(result.changedRowCount());
        meterRegistry.counter("ingest.announcement.catalog.rows", "change", "unchanged")
                .increment(result.unchangedRowCount());
    }

    @Override
    public String failureLogMessage() {
        return "LH 공고 목록 수집 실패";
    }
}

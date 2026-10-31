package com.toadzip.backend.ingest.collection.lh.announcementcatalog.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogPage;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogRow;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.dto.LhAnnouncementCatalogCollectedResponse;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.dto.LhAnnouncementCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository.LhAnnouncementCatalogApiRepository;
import com.toadzip.backend.ingest.collection.paging.domain.PagedCollectionBuffer;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallCounter;
import com.toadzip.backend.ingest.collection.service.ExternalDataRetryExecutor;
import com.toadzip.backend.ingest.collection.service.SourceCollectionAttempt;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.time.Clock;
import java.util.function.IntFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LhAnnouncementCatalogCollector {

    private final LhAnnouncementCatalogApiRepository apiRepository;
    private final LhAnnouncementCatalogStorageService storage;
    private final SourceCollectionRecordService records;
    private final Clock clock;

    LhAnnouncementCatalogStorageService.StoreResult collectWithinBatch(
            LhAnnouncementCatalogCollectionRequest request, ExternalDataRetryExecutor retry,
            ExternalDataCallCounter counter
    ) {

        return retry.collectPages(ExternalDataSource.LH_ANNOUNCEMENT_CATALOG, request::pageDescription, counter,
                page -> apiRepository.fetch(request, page), fetch -> collectPages(request, fetch));
    }

    private LhAnnouncementCatalogStorageService.StoreResult collectPages(
            LhAnnouncementCatalogCollectionRequest request,
            IntFunction<LhAnnouncementCatalogPage> fetch
    ) {
        return SourceCollectionAttempt.prepare(() -> new LhAnnouncementCatalogCollectionRequest(request.executionId(), request.pageSize(),
                        request.maxPages(), clock.instant()))
                .run(records, attempt -> fetchPages(attempt, fetch), storage::complete);
    }

    private LhAnnouncementCatalogCollectedResponse fetchPages(
            LhAnnouncementCatalogCollectionRequest request,
            IntFunction<LhAnnouncementCatalogPage> fetch
    ) {
        var buffer = new PagedCollectionBuffer<LhAnnouncementCatalogRow>("LH 공고 목록");
        LhAnnouncementCatalogPage first = null;
        for (int page = 1; page <= request.maxPages(); page++) {
            SourceCollectionAttempt.verifyCanContinue();
            LhAnnouncementCatalogPage current = fetch.apply(page);
            if (first == null) {
                first = current;
            }
            current.validateWindow(first);
            buffer.add(current.page(), row -> row.snapshot().validateIdentifiers());
            IngestExecutionScope.pageCompleted(page, current.page().totalCount(), request.pageSize());
            if (buffer.isComplete()) {
                SourcePage<LhAnnouncementCatalogRow> collected = buffer.finish();
                return new LhAnnouncementCatalogCollectedResponse(collected.totalCount(), first.startDate(),
                        first.endDate(), clock.instant(), collected.rows());
            }
        }
        buffer.finish();
        throw new IllegalStateException("LH 공고 목록의 수집 응답이 없습니다.");
    }
}

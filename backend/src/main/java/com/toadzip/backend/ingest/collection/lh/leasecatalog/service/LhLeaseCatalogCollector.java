package com.toadzip.backend.ingest.collection.lh.leasecatalog.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.dto.LhLeaseCatalogCollectedResponse;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.repository.LhLeaseCatalogApiRepository;
import com.toadzip.backend.ingest.collection.paging.domain.PagedCollectionBuffer;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallCounter;
import com.toadzip.backend.ingest.collection.service.ExternalDataRetryExecutor;
import com.toadzip.backend.ingest.collection.service.SourceCollectionAttempt;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.time.Clock;
import java.util.UUID;
import java.util.function.IntFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LhLeaseCatalogCollector {

    private final LhLeaseCatalogApiRepository apiRepository;
    private final LhLeaseCatalogStorageService storage;
    private final SourceCollectionRecordService records;
    private final Clock clock;

    UUID collectWithinBatch(
            LhLeaseCatalogCollectionRequest request, ExternalDataRetryExecutor retry,
            ExternalDataCallCounter counter
    ) {

        return retry.collectPages(ExternalDataSource.LH_LEASE_CATALOG, request::pageDescription, counter,
                page -> apiRepository.fetch(request, page), fetch -> collectPages(request, fetch));
    }

    private UUID collectPages(
            LhLeaseCatalogCollectionRequest request,
            IntFunction<SourcePage<LhCatalogSourceSnapshot>> fetch
    ) {
        return SourceCollectionAttempt.run(records,
                () -> new LhLeaseCatalogCollectionRequest(request.executionId(), request.pageSize(),
                        request.maxPages(), clock.instant()),
                attempt -> fetchPages(attempt, fetch), (recordId, attempt, page) -> {
                    storage.complete(recordId, attempt, new LhLeaseCatalogCollectedResponse(
                            page.totalCount(), clock.instant(), page.rows()));
                    return recordId;
                });
    }

    private SourcePage<LhCatalogSourceSnapshot> fetchPages(
            LhLeaseCatalogCollectionRequest request,
            IntFunction<SourcePage<LhCatalogSourceSnapshot>> fetch
    ) {
        var buffer = new PagedCollectionBuffer<LhCatalogSourceSnapshot>("LH 임대 카탈로그");
        for (int page = 1; page <= request.maxPages(); page++) {
            SourceCollectionAttempt.verifyCanContinue();
            var current = fetch.apply(page);
            buffer.add(current, LhCatalogSourceSnapshot::validateIdentifiers);
            IngestExecutionScope.pageCompleted(page, current.totalCount(), request.pageSize());
            if (buffer.isComplete()) {
                return buffer.finish();
            }
        }
        return buffer.finish();
    }
}

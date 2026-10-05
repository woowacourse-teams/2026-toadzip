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
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock.Operation;
import com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock;
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
    private final IngestOperationLock executionLock;
    private final Clock clock;

    public UUID collect(LhLeaseCatalogCollectionRequest request) {
        return executionLock.tryRun(Operation.LH_LEASE_CATALOG_COLLECTION,
                () -> collectUnlocked(request, page -> apiRepository.fetch(request, page)))
                .orElseThrow(() -> new IngestAlreadyRunningException("LH 임대 카탈로그 수집이 이미 실행 중입니다."));
    }

    UUID collectWithinBatch(
            LhLeaseCatalogCollectionRequest request, ExternalDataRetryExecutor retry,
            ExternalDataCallCounter counter
    ) {

        return retry.collectPages(ExternalDataSource.LH_LEASE_CATALOG,
                page -> "PG_SZ=" + request.pageSize() + "&PAGE=" + page, counter,
                page -> apiRepository.fetch(request, page), fetch -> collectUnlocked(request, fetch));
    }

    private UUID collectUnlocked(
            LhLeaseCatalogCollectionRequest request,
            IntFunction<SourcePage<LhCatalogSourceSnapshot>> fetch
    ) {
        verifyCanContinue();
        var attempt = new LhLeaseCatalogCollectionRequest(request.executionId(), request.pageSize(),
                request.maxPages(), clock.instant());
        UUID id = records.start(attempt);
        try {
            SourcePage<LhCatalogSourceSnapshot> page = fetchPages(attempt, fetch);
            IngestExecutionScope.verifyHeld();
            storage.complete(id, attempt, new LhLeaseCatalogCollectedResponse(
                    page.totalCount(), clock.instant(), page.rows()));
        } catch (RuntimeException failure) {
            records.fail(id, attempt, failure);
            throw failure;
        }
        return id;
    }

    private SourcePage<LhCatalogSourceSnapshot> fetchPages(
            LhLeaseCatalogCollectionRequest request,
            IntFunction<SourcePage<LhCatalogSourceSnapshot>> fetch
    ) {
        var buffer = new PagedCollectionBuffer<LhCatalogSourceSnapshot>("LH 임대 카탈로그");
        for (int page = 1; page <= request.maxPages(); page++) {
            verifyCanContinue();
            var current = fetch.apply(page);
            buffer.add(current, LhCatalogSourceSnapshot::validateIdentifiers);
            IngestExecutionScope.pageCompleted(page, current.totalCount(), request.pageSize());
            if (buffer.isComplete()) {
                return buffer.finish();
            }
        }
        return buffer.finish();
    }

    private void verifyCanContinue() {
        IngestExecutionScope.verifyHeld();
        IngestExecutionScope.checkStopRequested();
    }
}

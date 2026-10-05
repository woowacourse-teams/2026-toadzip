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
public class LhAnnouncementCatalogCollector {

    private final LhAnnouncementCatalogApiRepository apiRepository;
    private final LhAnnouncementCatalogStorageService storage;
    private final SourceCollectionRecordService records;
    private final IngestOperationLock executionLock;
    private final Clock clock;

    public UUID collect(LhAnnouncementCatalogCollectionRequest request) {
        return executionLock.tryRun(Operation.LH_ANNOUNCEMENT_COLLECTION,
                () -> collectUnlocked(request, page -> apiRepository.fetch(request, page)).id())
                .orElseThrow(() -> new IngestAlreadyRunningException("LH 공고 목록 수집이 이미 실행 중입니다."));
    }

    LhAnnouncementCatalogStorageService.StoreResult collectWithinBatch(
            LhAnnouncementCatalogCollectionRequest request, ExternalDataRetryExecutor retry,
            ExternalDataCallCounter counter
    ) {

        return retry.collectPages(ExternalDataSource.LH_ANNOUNCEMENT_CATALOG,
                page -> "PG_SZ=" + request.pageSize() + "&PAGE=" + page, counter,
                page -> apiRepository.fetch(request, page), fetch -> collectUnlocked(request, fetch).stored());
    }

    private CollectionResult collectUnlocked(
            LhAnnouncementCatalogCollectionRequest request,
            IntFunction<LhAnnouncementCatalogPage> fetch
    ) {
        verifyCanContinue();
        var attempt = new LhAnnouncementCatalogCollectionRequest(request.executionId(), request.pageSize(),
                request.maxPages(), clock.instant());
        UUID id = records.start(attempt);
        try {
            LhAnnouncementCatalogCollectedResponse response = fetchPages(attempt, fetch);
            IngestExecutionScope.verifyHeld();
            return new CollectionResult(id, storage.complete(id, attempt, response));
        } catch (RuntimeException failure) {
            records.fail(id, attempt, failure);
            throw failure;
        }
    }

    private LhAnnouncementCatalogCollectedResponse fetchPages(
            LhAnnouncementCatalogCollectionRequest request,
            IntFunction<LhAnnouncementCatalogPage> fetch
    ) {
        var buffer = new PagedCollectionBuffer<LhAnnouncementCatalogRow>("LH 공고 목록");
        LhAnnouncementCatalogPage first = null;
        for (int page = 1; page <= request.maxPages(); page++) {
            verifyCanContinue();
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

    private record CollectionResult(UUID id, LhAnnouncementCatalogStorageService.StoreResult stored) {
    }

    private void verifyCanContinue() {
        IngestExecutionScope.verifyHeld();
        IngestExecutionScope.checkStopRequested();
    }
}

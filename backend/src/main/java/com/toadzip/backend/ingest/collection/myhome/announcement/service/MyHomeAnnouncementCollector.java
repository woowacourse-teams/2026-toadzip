package com.toadzip.backend.ingest.collection.myhome.announcement.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementApiRepository;
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
public class MyHomeAnnouncementCollector {

    private final MyHomeAnnouncementStorageService storage;
    private final SourceCollectionRecordService records;
    private final MyHomeAnnouncementApiRepository apiRepository;
    private final IngestOperationLock executionLock;
    private final Clock clock;

    public UUID collect(MyHomeAnnouncementCollectionRequest request) {
        return executionLock.tryRun(Operation.MYHOME_ANNOUNCEMENT_COLLECTION,
                () -> collectUnlocked(request, page -> apiRepository.fetch(request, page)))
                .orElseThrow(() -> new IngestAlreadyRunningException("마이홈 공고 수집이 이미 실행 중입니다."));
    }

    UUID collectWithinBatch(
            MyHomeAnnouncementCollectionRequest request, ExternalDataRetryExecutor retry,
            ExternalDataCallCounter counter
    ) {

        return retry.collectPages(ExternalDataSource.MYHOME_ANNOUNCEMENT,
                page -> "suplyTy=" + request.supplyTypeCode() + "&pageNo=" + page
                        + "&numOfRows=" + request.pageSize(), counter,
                page -> apiRepository.fetch(request, page), fetch -> collectUnlocked(request, fetch));
    }

    private UUID collectUnlocked(
            MyHomeAnnouncementCollectionRequest request,
            IntFunction<SourcePage<MyHomeAnnouncementSourceSnapshot>> fetch
    ) {
        verifyCanContinue();
        var attempt = new MyHomeAnnouncementCollectionRequest(request.executionId(), request.supplyTypeCode(),
                request.pageSize(), request.maxPages(), clock.instant());
        UUID recordId = records.start(attempt);
        try {
            MyHomeAnnouncementCollectionBuffer buffer = fetchPages(attempt, fetch);
            IngestExecutionScope.verifyHeld();
            storage.complete(recordId, attempt, buffer.finish(clock.instant()));
        } catch (RuntimeException failure) {
            records.fail(recordId, attempt, failure);
            throw failure;
        }
        return recordId;
    }

    private MyHomeAnnouncementCollectionBuffer fetchPages(
            MyHomeAnnouncementCollectionRequest request,
            IntFunction<SourcePage<MyHomeAnnouncementSourceSnapshot>> fetch
    ) {
        var buffer = new MyHomeAnnouncementCollectionBuffer(request);
        for (int page = 1; page <= request.maxPages(); page++) {
            verifyCanContinue();
            var current = fetch.apply(page);
            buffer.add(current);
            IngestExecutionScope.pageCompleted(page, current.totalCount(), request.pageSize());
            if (buffer.isComplete()) {
                return buffer;
            }
        }
        return buffer;
    }

    private void verifyCanContinue() {
        IngestExecutionScope.verifyHeld();
        IngestExecutionScope.checkStopRequested();
    }
}

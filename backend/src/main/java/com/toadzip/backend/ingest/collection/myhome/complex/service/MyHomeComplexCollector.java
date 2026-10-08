package com.toadzip.backend.ingest.collection.myhome.complex.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexApiRepository;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeRegionCatalog;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallCounter;
import com.toadzip.backend.ingest.collection.service.ExternalDataRetryExecutor;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock.Operation;
import com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MyHomeComplexCollector {

    private final MyHomeComplexStorageService storage;
    private final SourceCollectionRecordService records;
    private final MyHomeComplexApiRepository apiRepository;
    private final MyHomeRegionCatalog regionCatalog;
    private final IngestOperationLock executionLock;
    private final Clock clock;

    public UUID collect(MyHomeComplexCollectionRequest request) {
        regionCatalog.find(request.provinceCode(), request.districtCode());
        return executionLock.tryRun(Operation.MYHOME_COMPLEX_COLLECTION,
                () -> collectUnlocked(request, page -> apiRepository.fetch(request, page)))
                .orElseThrow(() -> new IngestAlreadyRunningException("마이홈 단지 수집이 이미 실행 중입니다."));
    }

    UUID collectWithinBatch(
            MyHomeComplexCollectionRequest request, ExternalDataRetryExecutor retry, ExternalDataCallCounter counter,
            AtomicBoolean rateLimitReached
    ) {
        regionCatalog.find(request.provinceCode(), request.districtCode());
        return retry.collectPages(ExternalDataSource.MYHOME_COMPLEX,
                page -> "brtcCode=" + request.provinceCode() + "&signguCode=" + request.districtCode()
                        + "&pageNo=" + page + "&numOfRows=" + request.pageSize(), counter,
                page -> apiRepository.fetch(request, page), fetch -> collectUnlocked(request, fetch), () -> {
                    if (rateLimitReached.get()) {
                        throw new RateLimitCollectionCancelledException();
                    }
                });
    }

    private UUID collectUnlocked(
            MyHomeComplexCollectionRequest request,
            IntFunction<SourcePage<MyHomeComplexSourceSnapshot>> fetch
    ) {
        verifyCanContinue();
        var attempt = new MyHomeComplexCollectionRequest(request.executionId(), request.provinceCode(),
                request.districtCode(), request.pageSize(), request.maxPages(), clock.instant());
        UUID recordId = records.start(attempt);
        try {
            MyHomeComplexCollectionBuffer buffer = fetchPages(attempt, fetch);
            IngestExecutionScope.verifyHeld();
            storage.complete(recordId, attempt, buffer.finish(clock.instant()));
        } catch (RuntimeException failure) {
            records.fail(recordId, attempt, failure);
            throw failure;
        }
        return recordId;
    }

    private MyHomeComplexCollectionBuffer fetchPages(
            MyHomeComplexCollectionRequest request,
            IntFunction<SourcePage<MyHomeComplexSourceSnapshot>> fetch
    ) {
        var buffer = new MyHomeComplexCollectionBuffer(request);
        for (int page = 1; page <= request.maxPages(); page++) {
            verifyCanContinue();
            buffer.add(fetch.apply(page));
            if (buffer.isComplete()) {
                return buffer;
            }
        }
        return buffer;
    }

    static final class RateLimitCollectionCancelledException extends RuntimeException {
    }

    private void verifyCanContinue() {
        IngestExecutionScope.verifyHeld();
        IngestExecutionScope.checkStopRequested();
    }
}

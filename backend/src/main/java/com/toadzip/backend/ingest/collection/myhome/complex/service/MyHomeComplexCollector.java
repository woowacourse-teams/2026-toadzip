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
import com.toadzip.backend.ingest.collection.service.SourceCollectionAttempt;
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
    private final Clock clock;

    UUID collectWithinBatch(
            MyHomeComplexCollectionRequest request, ExternalDataRetryExecutor retry, ExternalDataCallCounter counter,
            AtomicBoolean rateLimitReached
    ) {
        regionCatalog.find(request.provinceCode(), request.districtCode());
        return retry.collectPages(ExternalDataSource.MYHOME_COMPLEX, request::pageDescription, counter,
                page -> apiRepository.fetch(request, page), fetch -> collectPages(request, fetch), () -> {
                    if (rateLimitReached.get()) {
                        throw new RateLimitCollectionCancelledException();
                    }
                });
    }

    private UUID collectPages(
            MyHomeComplexCollectionRequest request,
            IntFunction<SourcePage<MyHomeComplexSourceSnapshot>> fetch
    ) {
        return SourceCollectionAttempt.run(records,
                () -> new MyHomeComplexCollectionRequest(request.executionId(), request.provinceCode(),
                        request.districtCode(), request.pageSize(), request.maxPages(), clock.instant()),
                attempt -> fetchPages(attempt, fetch), (recordId, attempt, buffer) -> {
                    storage.complete(recordId, attempt, buffer.finish(clock.instant()));
                    return recordId;
                });
    }

    private MyHomeComplexCollectionBuffer fetchPages(
            MyHomeComplexCollectionRequest request,
            IntFunction<SourcePage<MyHomeComplexSourceSnapshot>> fetch
    ) {
        var buffer = new MyHomeComplexCollectionBuffer(request);
        for (int page = 1; page <= request.maxPages(); page++) {
            SourceCollectionAttempt.verifyCanContinue();
            buffer.add(fetch.apply(page));
            if (buffer.isComplete()) {
                return buffer;
            }
        }
        return buffer;
    }

    static final class RateLimitCollectionCancelledException extends RuntimeException {
    }
}

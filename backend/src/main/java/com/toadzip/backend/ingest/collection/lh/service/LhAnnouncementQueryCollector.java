package com.toadzip.backend.ingest.collection.lh.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.lh.detail.repository.LhDetailResponseParser;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementQueryApiRepository;
import com.toadzip.backend.ingest.collection.lh.supply.repository.LhSupplyResponseParser;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallCounter;
import com.toadzip.backend.ingest.collection.service.ExternalDataRetryExecutor;
import com.toadzip.backend.ingest.collection.service.SourceCollectionAttempt;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LhAnnouncementQueryCollector {

    private final LhAnnouncementQueryApiRepository api;

    private final LhSupplyResponseParser supplyParser;

    private final LhDetailResponseParser detailParser;

    private final LhAnnouncementStorageService storage;

    private final SourceCollectionRecordService records;

    private final Clock clock;

    UUID collectWithinBatch(
            LhAnnouncementCollectionRequest request, ExternalDataRetryExecutor retry, ExternalDataCallCounter counter,
            MeterRegistry meters
    ) {
        ExternalDataSource source = ExternalDataSource.valueOf(request.source().name());
        var attempt = SourceCollectionAttempt.prepare(() -> new LhAnnouncementCollectionRequest(
                request.executionId(), request.source(), request.query(), request.collectionVersion(), clock.instant()));
        if (request.source() == CollectionSource.LH_ANNOUNCEMENT_SUPPLY) {
            return attempt.run(records,
                    started -> retry.execute(source, request.description(),
                            () -> supplyParser.parse(request.query().supplyInfoTypeCode(), api.supply(request.query())),
                            counter),
                    (recordId, started, rows) -> store(meters, source, recordId,
                            () -> storage.completeSupply(recordId, started, clock.instant(), rows)));
        }
        return attempt.run(records,
                started -> retry.execute(source, request.description(),
                        () -> detailParser.parse(api.detail(request.query())), counter),
                (recordId, started, rows) -> store(meters, source, recordId,
                        () -> storage.completeDetail(recordId, started, clock.instant(), rows)));
    }

    private UUID store(MeterRegistry meters, ExternalDataSource source, UUID recordId, Runnable completion) {
        meters.timer("ingest.announcement.store", "source", source.name()).record(completion);
        return recordId;
    }
}

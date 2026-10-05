package com.toadzip.backend.ingest.collection.lh.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.detail.repository.LhDetailResponseParser;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementQueryApiRepository;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.supply.repository.LhSupplyResponseParser;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallCounter;
import com.toadzip.backend.ingest.collection.service.ExternalDataRetryExecutor;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
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

    public UUID collect(LhAnnouncementCollectionRequest request) {
        return collect(request,
                () -> supplyParser.parse(request.query().supplyInfoTypeCode(), api.supply(request.query())),
                () -> detailParser.parse(api.detail(request.query())), Runnable::run);
    }

    UUID collectWithinBatch(
            LhAnnouncementCollectionRequest request, ExternalDataRetryExecutor retry, ExternalDataCallCounter counter,
            MeterRegistry meters
    ) {
        ExternalDataSource source = ExternalDataSource.valueOf(request.source().name());
        return collect(request,
                () -> retry.execute(source, request.description(),
                        () -> supplyParser.parse(request.query().supplyInfoTypeCode(), api.supply(request.query())),
                        counter),
                () -> retry.execute(source, request.description(),
                        () -> detailParser.parse(api.detail(request.query())), counter),
                action -> meters.timer("ingest.announcement.store", "source", source.name()).record(action));
    }

    private UUID collect(
            LhAnnouncementCollectionRequest request,
            Supplier<List<LhAnnouncementSupplySourceSnapshot>> supplies,
            Supplier<List<LhAnnouncementDetailSourceSnapshot>> details, Consumer<Runnable> store
    ) {
        IngestExecutionScope.verifyHeld();
        IngestExecutionScope.checkStopRequested();
        var attempt = new LhAnnouncementCollectionRequest(request.executionId(), request.source(), request.query(),
                request.collectionVersion(), clock.instant());
        UUID id = records.start(attempt);
        try {
            collectResponse(id, attempt, supplies, details, store);
        } catch (RuntimeException failure) {
            records.fail(id, attempt, failure);
            throw failure;
        }
        return id;
    }

    private void collectResponse(
            UUID id, LhAnnouncementCollectionRequest request,
            Supplier<List<LhAnnouncementSupplySourceSnapshot>> supplies,
            Supplier<List<LhAnnouncementDetailSourceSnapshot>> details, Consumer<Runnable> store
    ) {
        if (request.source() == CollectionSource.LH_ANNOUNCEMENT_SUPPLY) {
            var rows = supplies.get();
            IngestExecutionScope.verifyHeld();
            store.accept(() -> storage.completeSupply(id, request, clock.instant(), rows));
            return;
        }
        var rows = details.get();
        IngestExecutionScope.verifyHeld();
        store.accept(() -> storage.completeDetail(id, request, clock.instant(), rows));
    }
}

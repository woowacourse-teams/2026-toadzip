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
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
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

    private final ExternalDataRetryExecutor retry;

    private final MeterRegistry meters;

    private final Clock clock;

    /** LH 공고 공급·상세 원천 하나를 조회해 저장하고 저장한 행 수를 돌려준다. 실패는 기록하지 않고 그대로 던진다. */
    int collect(Function<Instant, LhAnnouncementCollectionRequest> newRequest, ExternalDataCallCounter counter) {
        var attempt = SourceCollectionAttempt.prepare(() -> newRequest.apply(clock.instant()));
        LhAnnouncementCollectionRequest request = attempt.request();
        ExternalDataSource source = ExternalDataSource.valueOf(request.source().name());
        UUID recordId;
        if (request.source() == CollectionSource.LH_ANNOUNCEMENT_SUPPLY) {
            recordId = attempt.run(records,
                    started -> retry.execute(source, request.description(),
                            () -> supplyParser.parse(request.query().supplyInfoTypeCode(), api.supply(request.query())),
                            counter),
                    (id, started, rows) -> store(source,
                            () -> storage.completeSupply(id, started, clock.instant(), rows)));
        } else {
            recordId = attempt.run(records,
                    started -> retry.execute(source, request.description(),
                            () -> detailParser.parse(api.detail(request.query())), counter),
                    (id, started, rows) -> store(source,
                            () -> storage.completeDetail(id, started, clock.instant(), rows)));
        }
        return records.storedRowCount(recordId);
    }

    private void store(ExternalDataSource source, Runnable completion) {
        meters.timer("ingest.announcement.store", "source", source.name()).record(completion);
    }
}

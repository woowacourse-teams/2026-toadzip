package com.toadzip.backend.ingest.collection.myhome.announcement.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSupplyType;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectedResponse;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementApiRepository;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallCounter;
import com.toadzip.backend.ingest.collection.service.ExternalDataRetryExecutor;
import com.toadzip.backend.ingest.collection.service.SourceCollectionAttempt;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
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
    private final Clock clock;

    /** 목록의 모든 페이지를 검증하지만 해당 공고만 저장한다. 전체 수집 완료 처리는 하지 않는다. */
    public int collectAnnouncement(String pblancId, ExternalDataRetryExecutor retry) {
        UUID executionId = IngestExecutionScope.currentExecutionId().orElseGet(UUID::randomUUID);
        var counter = new ExternalDataCallCounter();
        int stored = 0;
        for (MyHomeAnnouncementSupplyType type : MyHomeAnnouncementSupplyType.values()) {
            var request = new MyHomeAnnouncementCollectionRequest(
                    executionId, type.requestCode(), 500, 1000, clock.instant(), pblancId);
            UUID recordId = collectWithinBatch(request, retry, counter);
            stored += records.storedRowCount(recordId);
        }
        if (stored == 0) {
            throw new InvalidIngestRequestException("공고를 찾을 수 없습니다: " + pblancId);
        }
        return stored;
    }

    UUID collectWithinBatch(
            MyHomeAnnouncementCollectionRequest request, ExternalDataRetryExecutor retry,
            ExternalDataCallCounter counter
    ) {

        return retry.collectPages(ExternalDataSource.MYHOME_ANNOUNCEMENT, request::pageDescription, counter,
                page -> apiRepository.fetch(request, page), fetch -> collectPages(request, fetch));
    }

    private UUID collectPages(
            MyHomeAnnouncementCollectionRequest request,
            IntFunction<SourcePage<MyHomeAnnouncementSourceSnapshot>> fetch
    ) {
        return SourceCollectionAttempt.prepare(() -> new MyHomeAnnouncementCollectionRequest(request.executionId(), request.supplyTypeCode(),
                        request.pageSize(), request.maxPages(), clock.instant(), request.pblancId()))
                .run(records, attempt -> fetchPages(attempt, fetch), (recordId, attempt, buffer) -> {
                    MyHomeAnnouncementCollectedResponse response = buffer.finish(clock.instant());
                    if (attempt.pblancId() != null) {
                        var rows = response.rows().stream()
                                .filter(row -> attempt.pblancId().equals(row.pblancId().strip())).toList();
                        response = new MyHomeAnnouncementCollectedResponse(rows.size(), response.collectedAt(), rows);
                    }
                    storage.complete(recordId, attempt, response);
                    return recordId;
                });
    }

    private MyHomeAnnouncementCollectionBuffer fetchPages(
            MyHomeAnnouncementCollectionRequest request,
            IntFunction<SourcePage<MyHomeAnnouncementSourceSnapshot>> fetch
    ) {
        var buffer = new MyHomeAnnouncementCollectionBuffer(request);
        for (int page = 1; page <= request.maxPages(); page++) {
            SourceCollectionAttempt.verifyCanContinue();
            var current = fetch.apply(page);
            buffer.add(current);
            IngestExecutionScope.pageCompleted(page, current.totalCount(), request.pageSize());
            if (buffer.isComplete()) {
                return buffer;
            }
        }
        return buffer;
    }
}

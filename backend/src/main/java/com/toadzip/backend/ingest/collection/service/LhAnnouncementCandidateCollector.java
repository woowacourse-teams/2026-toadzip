package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.repository.LhSourceStore;
import com.toadzip.backend.ingest.collection.repository.external.LhAnnouncementDetailResponseParser;
import com.toadzip.backend.ingest.collection.repository.external.LhAnnouncementSupplyResponseParser;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.exception.exception.EmptyLhDetailReplacementException;
import com.toadzip.backend.ingest.exception.exception.EmptyLhSupplyReplacementException;
import com.toadzip.backend.ingest.exception.exception.IncompleteLhSupplyReplacementException;
import com.toadzip.backend.ingest.exception.exception.LhAnnouncementUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 선정된 요청 하나를 조회·파싱하고 저장한 뒤 수집 완료를 기록한다.
 * 재시도는 외부 조회와 파싱에만 적용하며 저장과 완료 기록의 트랜잭션은 각 저장소·관리자가 소유한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LhAnnouncementCandidateCollector {

    private final LhAnnouncementExternalRepository externalRepository;
    private final LhAnnouncementDetailResponseParser detailResponseParser;
    private final LhAnnouncementSupplyResponseParser supplyResponseParser;
    private final ExternalDataRetryExecutor retryExecutor;
    private final LhSourceStore sourceStore;
    private final ExternalDataFailureRecorder failureRecorder;
    private final LhAnnouncementCollectionProgressManager progressManager;
    private final MeterRegistry meterRegistry;

    public ExternalDataCollectionReport collect(ExternalDataSource targetSource, Candidate candidate) {
        LhAnnouncementRequest request = candidate.request();
        ExternalDataCallCounter callCounter = new ExternalDataCallCounter();
        int storedRowCount;
        try {
            storedRowCount = collectAndStore(targetSource, request, callCounter);
        }
        catch (ExternalDataCallFailureException | EmptyLhDetailReplacementException
                | EmptyLhSupplyReplacementException
                | IncompleteLhSupplyReplacementException exception) {
            return failedReport(targetSource, request, exception, callCounter);
        }
        catch (LhAnnouncementUnavailableException exception) {
            if (!exception.isRateLimited()) {
                throw exception;
            }
            return failedReport(targetSource, request, exception, callCounter);
        }
        progressManager.complete(targetSource, candidate);
        return new ExternalDataCollectionReport(
                targetSource.operation(),
                storedRowCount,
                0,
                callCounter.count(),
                0,
                0,
                1
        );
    }

    private ExternalDataCollectionReport failedReport(
            ExternalDataSource targetSource,
            LhAnnouncementRequest request,
            RuntimeException exception,
            ExternalDataCallCounter callCounter
    ) {
        failureRecorder.record(
                targetSource,
                request.requestDescription(),
                exception,
                log,
                "LH 외부 API 수집에 실패했습니다"
        );
        return new ExternalDataCollectionReport(
                targetSource.operation(),
                0,
                1,
                callCounter.count(),
                0,
                ExternalDataRateLimit.count(exception)
        );
    }

    private int collectAndStore(
            ExternalDataSource targetSource,
            LhAnnouncementRequest request,
            ExternalDataCallCounter callCounter
    ) {
        if (targetSource == ExternalDataSource.LH_ANNOUNCEMENT_DETAIL) {
            List<LhAnnouncementDetailSource> details = retryExecutor.execute(
                    ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                    request.requestDescription(),
                    () -> detailResponseParser.parse(request.panId(), externalRepository.fetchDetail(request)),
                    callCounter
            );
            return meterRegistry.timer("ingest.announcement.store", "source", targetSource.name())
                    .record(() -> sourceStore.replaceDetails(
                            request.panId(), request.requestDescription(), details
                    ));
        }
        List<LhAnnouncementSupplySource> supplies = retryExecutor.execute(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                request.requestDescription(),
                () -> supplyResponseParser.parse(
                        request.panId(), request.supplyInfoTypeCode(), externalRepository.fetchSupply(request)
                ),
                callCounter
        );
        return meterRegistry.timer("ingest.announcement.store", "source", targetSource.name())
                .record(() -> sourceStore.replaceSupplies(
                        request.panId(), request.requestDescription(), supplies
                ));
    }

}

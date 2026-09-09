package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.repository.LhSourceStore;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.repository.external.LhAnnouncementDetailResponseParser;
import com.toadzip.backend.ingest.collection.repository.external.LhAnnouncementSupplyResponseParser;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LhAnnouncementCandidateCollector {

    private final LhAnnouncementExternalRepository externalRepository;
    private final LhSourceStore sourceStore;
    private final LhAnnouncementDetailResponseParser detailResponseParser;
    private final LhAnnouncementSupplyResponseParser supplyResponseParser;
    private final ExternalDataFailureRecorder failureRecorder;
    private final ExternalDataRetryExecutor retryExecutor;
    private final LhAnnouncementCollectionProgressManager progressManager;

    public LhAnnouncementCandidateCollector(
            LhAnnouncementExternalRepository externalRepository,
            LhSourceStore sourceStore,
            LhAnnouncementDetailResponseParser detailResponseParser,
            LhAnnouncementSupplyResponseParser supplyResponseParser,
            ExternalDataFailureRecorder failureRecorder,
            ExternalDataRetryExecutor retryExecutor,
            LhAnnouncementCollectionProgressManager progressManager
    ) {
        this.externalRepository = externalRepository;
        this.sourceStore = sourceStore;
        this.detailResponseParser = detailResponseParser;
        this.supplyResponseParser = supplyResponseParser;
        this.failureRecorder = failureRecorder;
        this.retryExecutor = retryExecutor;
        this.progressManager = progressManager;
    }

    public ExternalDataCollectionReport collect(ExternalDataSource targetSource, Candidate candidate) {
        LhAnnouncementRequest request = candidate.request();
        ExternalDataCallCounter callCounter = new ExternalDataCallCounter();
        ExternalDataResponse response;
        try {
            response = retryExecutor.execute(
                    targetSource,
                    request.requestDescription(),
                    () -> fetch(targetSource, request),
                    callCounter
            );
        }
        catch (ExternalDataCallFailureException exception) {
            return failedReport(targetSource, request, exception, callCounter);
        }
        int storedRowCount;
        try {
            storedRowCount = store(targetSource, request.panId(), response);
        }
        catch (ExternalDataRequestException exception) {
            return failedReport(targetSource, request, exception, callCounter);
        }
        progressManager.complete(targetSource, candidate);
        return new ExternalDataCollectionReport(targetSource.operation(), storedRowCount, 0, callCounter.count());
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

    private int store(ExternalDataSource targetSource, String panId, ExternalDataResponse response) {
        if (targetSource == ExternalDataSource.LH_ANNOUNCEMENT_DETAIL) {
            List<LhAnnouncementDetailSource> sources = detailResponseParser.parse(panId, response.body());
            return sourceStore.replaceDetails(panId, sources);
        }
        List<LhAnnouncementSupplySource> sources = supplyResponseParser.parse(panId, response.body());
        return sourceStore.replaceSupplies(panId, sources);
    }

    private ExternalDataResponse fetch(ExternalDataSource targetSource, LhAnnouncementRequest request) {
        if (targetSource == ExternalDataSource.LH_ANNOUNCEMENT_DETAIL) {
            return externalRepository.fetchDetail(request);
        }
        return externalRepository.fetchSupply(request);
    }

}

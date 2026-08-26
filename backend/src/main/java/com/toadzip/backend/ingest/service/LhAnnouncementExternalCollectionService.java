package com.toadzip.backend.ingest.service;

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.repository.LhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.repository.LhAnnouncementCollectionExecutionLock;
import com.toadzip.backend.ingest.repository.LhAnnouncementCollectionProgressStore;
import com.toadzip.backend.ingest.repository.LhSourceStore;
import com.toadzip.backend.ingest.repository.MyHomeAnnouncementSourceRepository;

@Slf4j
@Service
public class LhAnnouncementExternalCollectionService {

    private final MyHomeAnnouncementSourceRepository myHomeAnnouncementRepository;
    private final LhAnnouncementExternalRepository externalRepository;
    private final LhAnnouncementCollectionExecutionLock executionLock;
    private final LhSourceStore sourceStore;
    private final LhAnnouncementCollectionProgressStore progressStore;
    private final LhAnnouncementSourceMapper sourceMapper;
    private final ExternalDataFailureRecorder failureRecorder;
    private final LhSupplyInfoTypeCodeResolver supplyTypeCodeResolver;
    private final ExternalDataRetryExecutor retryExecutor;

    public LhAnnouncementExternalCollectionService(
            MyHomeAnnouncementSourceRepository myHomeAnnouncementRepository,
            LhAnnouncementExternalRepository externalRepository,
            LhAnnouncementCollectionExecutionLock executionLock,
            LhSourceStore sourceStore,
            LhAnnouncementCollectionProgressStore progressStore,
            LhAnnouncementSourceMapper sourceMapper,
            ExternalDataFailureRecorder failureRecorder,
            LhSupplyInfoTypeCodeResolver supplyTypeCodeResolver,
            ExternalDataRetryExecutor retryExecutor
    ) {
        this.myHomeAnnouncementRepository = myHomeAnnouncementRepository;
        this.externalRepository = externalRepository;
        this.executionLock = executionLock;
        this.sourceStore = sourceStore;
        this.progressStore = progressStore;
        this.sourceMapper = sourceMapper;
        this.failureRecorder = failureRecorder;
        this.supplyTypeCodeResolver = supplyTypeCodeResolver;
        this.retryExecutor = retryExecutor;
    }

    public ExternalDataCollectionReport collect(ExternalDataSource targetSource) {
        validateTargetSource(targetSource);
        log.info("{} 수집을 시작합니다.", operation(targetSource));
        ExternalDataCollectionReport report = executionLock
                .tryRun(targetSource, () -> collectAnnouncements(targetSource))
                .orElseThrow(() -> alreadyRunning(targetSource));
        log.info(
                "{} 수집을 완료했습니다: storedRowCount={}, failedRequestCount={}, externalApiCallCount={}",
                operation(targetSource),
                report.storedRowCount(),
                report.failedRequestCount(),
                report.externalApiCallCount()
        );
        return report;
    }

    private ExternalDataCollectionReport collectAnnouncements(ExternalDataSource targetSource) {
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty(operation(targetSource));
        Set<String> attemptedRequests = new HashSet<>();
        for (MyHomeAnnouncementSource source : distinctAnnouncements()) {
            report = report.plus(collectAnnouncement(targetSource, source, attemptedRequests));
        }
        return report;
    }

    private List<MyHomeAnnouncementSource> distinctAnnouncements() {
        Map<String, MyHomeAnnouncementSource> sources = new LinkedHashMap<>();
        for (MyHomeAnnouncementSource source : myHomeAnnouncementRepository.findAll()) {
            String key = sourceAnnouncementKey(source);
            sources.putIfAbsent(key, source);
        }
        return List.copyOf(sources.values());
    }

    private ExternalDataCollectionReport collectAnnouncement(
            ExternalDataSource targetSource,
            MyHomeAnnouncementSource source,
            Set<String> attemptedRequests
    ) {
        Optional<LhAnnouncementRequest> request = requestOf(source);
        if (request.isEmpty()) {
            return recordInvalidSource(targetSource, source);
        }
        LhAnnouncementRequest resolved = request.orElseThrow();
        String sourceAnnouncementKey = sourceAnnouncementKey(source);
        String requestDescription = resolved.requestDescription();
        if (!attemptedRequests.add(requestDescription)) {
            return ExternalDataCollectionReport.empty(operation(targetSource));
        }
        if (progressStore.isCompleted(targetSource, requestDescription)) {
            return ExternalDataCollectionReport.empty(operation(targetSource));
        }
        if (progressStore.hasStoredRows(targetSource, resolved.panId())
                && !progressStore.hasCollectionHistory(targetSource, resolved.panId())) {
            progressStore.complete(targetSource, sourceAnnouncementKey, requestDescription, resolved.panId());
            return ExternalDataCollectionReport.empty(operation(targetSource));
        }
        return fetchAndStore(targetSource, sourceAnnouncementKey, resolved);
    }

    private ExternalDataCollectionReport fetchAndStore(
            ExternalDataSource targetSource,
            String sourceAnnouncementKey,
            LhAnnouncementRequest request
    ) {
        ExternalDataCallCounter callCounter = new ExternalDataCallCounter();
        try {
            ExternalDataResponse response = retryExecutor.execute(
                    targetSource,
                    request.requestDescription(),
                    () -> fetch(targetSource, request),
                    callCounter
            );
            failureRecorder.resolve(targetSource, request.requestDescription());
            int storedRowCount = store(targetSource, request.panId(), response);
            progressStore.complete(
                    targetSource,
                    sourceAnnouncementKey,
                    request.requestDescription(),
                    request.panId()
            );
            return new ExternalDataCollectionReport(operation(targetSource), storedRowCount, 0, callCounter.count());
        }
        catch (RuntimeException exception) {
            failureRecorder.record(
                    targetSource,
                    request.requestDescription(),
                    exception,
                    log,
                    "LH 외부 API 수집에 실패했습니다"
            );
            return new ExternalDataCollectionReport(operation(targetSource), 0, 1, callCounter.count());
        }
    }

    private int store(ExternalDataSource targetSource, String panId, ExternalDataResponse response) {
        if (targetSource == ExternalDataSource.LH_ANNOUNCEMENT_DETAIL) {
            List<LhAnnouncementDetailSource> sources = sourceMapper.details(panId, response.body());
            return sourceStore.replaceDetails(panId, sources);
        }
        List<LhAnnouncementSupplySource> sources = sourceMapper.supplies(panId, response.body());
        return sourceStore.replaceSupplies(panId, sources);
    }

    private ExternalDataCollectionReport recordInvalidSource(
            ExternalDataSource targetSource,
            MyHomeAnnouncementSource source
    ) {
        IllegalStateException exception = new IllegalStateException("LH 공고 조회 조건이 없습니다.");
        failureRecorder.record(
                targetSource,
                sourceDescription(source),
                exception,
                log,
                "LH 공고 조회 조건 생성에 실패했습니다"
        );
        return new ExternalDataCollectionReport(operation(targetSource), 0, 1, 0);
    }

    private Optional<LhAnnouncementRequest> requestOf(MyHomeAnnouncementSource source) {
        Optional<String> supplyTypeCode = supplyTypeCodeResolver.resolve(source.getSuplyTyNm());
        if (supplyTypeCode.isEmpty()) {
            return Optional.empty();
        }
        String url = source.getUrl();
        if (url == null || url.isBlank()) {
            url = source.getPcUrl();
        }
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            return LhAnnouncementRequest.from(URI.create(url), supplyTypeCode.orElseThrow());
        }
        catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private ExternalDataResponse fetch(ExternalDataSource targetSource, LhAnnouncementRequest request) {
        if (targetSource == ExternalDataSource.LH_ANNOUNCEMENT_DETAIL) {
            return externalRepository.fetchDetail(request);
        }
        return externalRepository.fetchSupply(request);
    }

    private String sourceDescription(MyHomeAnnouncementSource source) {
        if (source.getId() == null) {
            return source.getSourceKey();
        }
        return "myhomeAnnouncementSourceId=" + source.getId();
    }

    private String sourceAnnouncementKey(MyHomeAnnouncementSource source) {
        String pblancId = source.getPblancId();
        if (pblancId == null || pblancId.isBlank()) {
            return "source:" + source.getSourceKey();
        }
        return pblancId;
    }

    private String operation(ExternalDataSource targetSource) {
        if (targetSource == ExternalDataSource.LH_ANNOUNCEMENT_DETAIL) {
            return "lh-announcement-detail";
        }
        return "lh-announcement-supply";
    }

    private void validateTargetSource(ExternalDataSource targetSource) {
        boolean supported = targetSource == ExternalDataSource.LH_ANNOUNCEMENT_DETAIL
                || targetSource == ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY;
        if (!supported) {
            throw new IllegalArgumentException("LH 공고 API가 아닙니다.");
        }
    }

    private IngestAlreadyRunningException alreadyRunning(ExternalDataSource targetSource) {
        log.warn("{} 수집이 이미 실행 중이므로 중복 실행을 건너뜁니다.", operation(targetSource));
        return new IngestAlreadyRunningException(operation(targetSource) + " 수집이 이미 실행 중입니다.");
    }
}

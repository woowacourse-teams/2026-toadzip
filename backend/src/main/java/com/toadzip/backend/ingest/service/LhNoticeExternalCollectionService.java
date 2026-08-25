package com.toadzip.backend.ingest.service;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.domain.LhNoticeDetailSource;
import com.toadzip.backend.ingest.domain.LhNoticeSupplySource;
import com.toadzip.backend.ingest.domain.MyHomeNoticeSource;
import com.toadzip.backend.ingest.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.dto.LhNoticeRequest;
import com.toadzip.backend.ingest.repository.LhNoticeExternalRepository;
import com.toadzip.backend.ingest.repository.LhNoticeCollectionExecutionLock;
import com.toadzip.backend.ingest.repository.LhSourceStore;
import com.toadzip.backend.ingest.repository.MyHomeNoticeSourceRepository;

@Slf4j
@Service
public class LhNoticeExternalCollectionService {

    private final MyHomeNoticeSourceRepository myHomeNoticeRepository;
    private final LhNoticeExternalRepository externalRepository;
    private final LhNoticeCollectionExecutionLock executionLock;
    private final LhSourceStore sourceStore;
    private final LhNoticeSourceMapper sourceMapper;
    private final ExternalDataFailureRecorder failureRecorder;
    private final LhSupplyInfoTypeCodeResolver supplyTypeCodeResolver;
    private final ExternalDataRetryExecutor retryExecutor;

    public LhNoticeExternalCollectionService(
            MyHomeNoticeSourceRepository myHomeNoticeRepository,
            LhNoticeExternalRepository externalRepository,
            LhNoticeCollectionExecutionLock executionLock,
            LhSourceStore sourceStore,
            LhNoticeSourceMapper sourceMapper,
            ExternalDataFailureRecorder failureRecorder,
            LhSupplyInfoTypeCodeResolver supplyTypeCodeResolver,
            ExternalDataRetryExecutor retryExecutor
    ) {
        this.myHomeNoticeRepository = myHomeNoticeRepository;
        this.externalRepository = externalRepository;
        this.executionLock = executionLock;
        this.sourceStore = sourceStore;
        this.sourceMapper = sourceMapper;
        this.failureRecorder = failureRecorder;
        this.supplyTypeCodeResolver = supplyTypeCodeResolver;
        this.retryExecutor = retryExecutor;
    }

    public ExternalDataCollectionReport collect(ExternalDataSource targetSource) {
        validateTargetSource(targetSource);
        log.info("{} 수집을 시작합니다.", operation(targetSource));
        ExternalDataCollectionReport report = executionLock.tryRun(targetSource, () -> collectNotices(targetSource))
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

    private ExternalDataCollectionReport collectNotices(ExternalDataSource targetSource) {
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty(operation(targetSource));
        for (MyHomeNoticeSource source : distinctNotices()) {
            report = report.plus(collectNotice(targetSource, source));
        }
        return report;
    }

    private List<MyHomeNoticeSource> distinctNotices() {
        Map<String, MyHomeNoticeSource> sources = new LinkedHashMap<>();
        for (MyHomeNoticeSource source : myHomeNoticeRepository.findAll()) {
            String key = source.getPblancId();
            if (key == null || key.isBlank()) {
                key = "source:" + source.getSourceKey();
            }
            sources.putIfAbsent(key, source);
        }
        return List.copyOf(sources.values());
    }

    private ExternalDataCollectionReport collectNotice(ExternalDataSource targetSource, MyHomeNoticeSource source) {
        Optional<LhNoticeRequest> request = requestOf(source);
        if (request.isEmpty()) {
            return recordInvalidSource(targetSource, source);
        }
        LhNoticeRequest resolved = request.orElseThrow();
        return fetchAndStore(targetSource, resolved);
    }

    private ExternalDataCollectionReport fetchAndStore(ExternalDataSource targetSource, LhNoticeRequest request) {
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
        if (targetSource == ExternalDataSource.LH_NOTICE_DETAIL) {
            List<LhNoticeDetailSource> sources = sourceMapper.details(panId, response.body());
            return sourceStore.replaceDetails(panId, sources);
        }
        List<LhNoticeSupplySource> sources = sourceMapper.supplies(panId, response.body());
        return sourceStore.replaceSupplies(panId, sources);
    }

    private ExternalDataCollectionReport recordInvalidSource(
            ExternalDataSource targetSource,
            MyHomeNoticeSource source
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

    private Optional<LhNoticeRequest> requestOf(MyHomeNoticeSource source) {
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
            return LhNoticeRequest.from(URI.create(url), supplyTypeCode.orElseThrow());
        }
        catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private ExternalDataResponse fetch(ExternalDataSource targetSource, LhNoticeRequest request) {
        if (targetSource == ExternalDataSource.LH_NOTICE_DETAIL) {
            return externalRepository.fetchDetail(request);
        }
        return externalRepository.fetchSupply(request);
    }

    private String sourceDescription(MyHomeNoticeSource source) {
        if (source.getId() == null) {
            return source.getSourceKey();
        }
        return "myhomeNoticeSourceId=" + source.getId();
    }

    private String operation(ExternalDataSource targetSource) {
        if (targetSource == ExternalDataSource.LH_NOTICE_DETAIL) {
            return "lh-notice-detail";
        }
        return "lh-notice-supply";
    }

    private void validateTargetSource(ExternalDataSource targetSource) {
        if (targetSource != ExternalDataSource.LH_NOTICE_DETAIL && targetSource != ExternalDataSource.LH_NOTICE_SUPPLY) {
            throw new IllegalArgumentException("LH 공고 API가 아닙니다.");
        }
    }

    private IngestAlreadyRunningException alreadyRunning(ExternalDataSource targetSource) {
        log.warn("{} 수집이 이미 실행 중이므로 중복 실행을 건너뜁니다.", operation(targetSource));
        return new IngestAlreadyRunningException(operation(targetSource) + " 수집이 이미 실행 중입니다.");
    }
}

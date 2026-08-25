package com.toadzip.backend.ingest.service;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.domain.ExternalApi;
import com.toadzip.backend.ingest.domain.LhNoticeDetailSource;
import com.toadzip.backend.ingest.domain.LhNoticeSupplySource;
import com.toadzip.backend.ingest.domain.MyHomeNoticeSource;
import com.toadzip.backend.ingest.dto.ExternalApiCollectionReport;
import com.toadzip.backend.ingest.dto.ExternalApiResponse;
import com.toadzip.backend.ingest.dto.LhNoticeRequest;
import com.toadzip.backend.ingest.repository.LhNoticeApiRepository;
import com.toadzip.backend.ingest.repository.LhNoticeCollectionExecutionLock;
import com.toadzip.backend.ingest.repository.LhSourceStore;
import com.toadzip.backend.ingest.repository.MyHomeNoticeSourceRepository;

@Slf4j
@Service
public class LhNoticeApiCollectionService {

    private final MyHomeNoticeSourceRepository myHomeNoticeRepository;
    private final LhNoticeApiRepository apiRepository;
    private final LhNoticeCollectionExecutionLock executionLock;
    private final LhSourceStore sourceStore;
    private final LhNoticeSourceMapper sourceMapper;
    private final ExternalApiFailureRecorder failureRecorder;
    private final LhSupplyInfoTypeCodeResolver supplyTypeCodeResolver;
    private final ExternalApiRetryExecutor retryExecutor;

    public LhNoticeApiCollectionService(
            MyHomeNoticeSourceRepository myHomeNoticeRepository,
            LhNoticeApiRepository apiRepository,
            LhNoticeCollectionExecutionLock executionLock,
            LhSourceStore sourceStore,
            LhNoticeSourceMapper sourceMapper,
            ExternalApiFailureRecorder failureRecorder,
            LhSupplyInfoTypeCodeResolver supplyTypeCodeResolver,
            ExternalApiRetryExecutor retryExecutor
    ) {
        this.myHomeNoticeRepository = myHomeNoticeRepository;
        this.apiRepository = apiRepository;
        this.executionLock = executionLock;
        this.sourceStore = sourceStore;
        this.sourceMapper = sourceMapper;
        this.failureRecorder = failureRecorder;
        this.supplyTypeCodeResolver = supplyTypeCodeResolver;
        this.retryExecutor = retryExecutor;
    }

    public ExternalApiCollectionReport collect(ExternalApi targetApi) {
        validateTargetApi(targetApi);
        return executionLock.tryRun(targetApi, () -> collectNotices(targetApi))
                .orElseThrow(() -> alreadyRunning(targetApi));
    }

    private ExternalApiCollectionReport collectNotices(ExternalApi targetApi) {
        ExternalApiCollectionReport report = ExternalApiCollectionReport.empty(operation(targetApi));
        for (MyHomeNoticeSource source : distinctNotices()) {
            report = report.plus(collectNotice(targetApi, source));
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

    private ExternalApiCollectionReport collectNotice(ExternalApi targetApi, MyHomeNoticeSource source) {
        Optional<LhNoticeRequest> request = requestOf(source);
        if (request.isEmpty()) {
            return recordInvalidSource(targetApi, source);
        }
        LhNoticeRequest resolved = request.orElseThrow();
        return fetchAndStore(targetApi, resolved);
    }

    private ExternalApiCollectionReport fetchAndStore(ExternalApi targetApi, LhNoticeRequest request) {
        try {
            ExternalApiCallCounter callCounter = new ExternalApiCallCounter();
            ExternalApiResponse response = retryExecutor.execute(
                    targetApi,
                    request.requestDescription(),
                    () -> fetch(targetApi, request),
                    callCounter
            );
            int storedRowCount = store(targetApi, request.panId(), response);
            return new ExternalApiCollectionReport(operation(targetApi), storedRowCount, 0);
        }
        catch (RuntimeException exception) {
            failureRecorder.record(
                    targetApi,
                    request.requestDescription(),
                    exception,
                    log,
                    "LH 외부 API 수집에 실패했습니다"
            );
            return new ExternalApiCollectionReport(operation(targetApi), 0, 1);
        }
    }

    private int store(ExternalApi targetApi, String panId, ExternalApiResponse response) {
        if (targetApi == ExternalApi.LH_NOTICE_DETAIL) {
            List<LhNoticeDetailSource> sources = sourceMapper.details(panId, response.responseBody());
            return sourceStore.replaceDetails(panId, sources);
        }
        List<LhNoticeSupplySource> sources = sourceMapper.supplies(panId, response.responseBody());
        return sourceStore.replaceSupplies(panId, sources);
    }

    private ExternalApiCollectionReport recordInvalidSource(
            ExternalApi targetApi,
            MyHomeNoticeSource source
    ) {
        IllegalStateException exception = new IllegalStateException("LH 공고 조회 조건이 없습니다.");
        failureRecorder.record(
                targetApi,
                sourceDescription(source),
                exception,
                log,
                "LH 공고 조회 조건 생성에 실패했습니다"
        );
        return new ExternalApiCollectionReport(operation(targetApi), 0, 1);
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

    private ExternalApiResponse fetch(ExternalApi targetApi, LhNoticeRequest request) {
        if (targetApi == ExternalApi.LH_NOTICE_DETAIL) {
            return apiRepository.fetchDetail(request);
        }
        return apiRepository.fetchSupply(request);
    }

    private String sourceDescription(MyHomeNoticeSource source) {
        if (source.getId() == null) {
            return source.getSourceKey();
        }
        return "myhomeNoticeSourceId=" + source.getId();
    }

    private String operation(ExternalApi targetApi) {
        if (targetApi == ExternalApi.LH_NOTICE_DETAIL) {
            return "lh-notice-detail";
        }
        return "lh-notice-supply";
    }

    private void validateTargetApi(ExternalApi targetApi) {
        if (targetApi != ExternalApi.LH_NOTICE_DETAIL && targetApi != ExternalApi.LH_NOTICE_SUPPLY) {
            throw new IllegalArgumentException("LH 공고 API가 아닙니다.");
        }
    }

    private IngestAlreadyRunningException alreadyRunning(ExternalApi targetApi) {
        log.warn("{} 수집이 이미 실행 중이므로 중복 실행을 건너뜁니다.", operation(targetApi));
        return new IngestAlreadyRunningException(operation(targetApi) + " 수집이 이미 실행 중입니다.");
    }
}

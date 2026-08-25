package com.toadzip.backend.ingest.service;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.domain.ExternalApi;
import com.toadzip.backend.ingest.domain.ExternalApiData;
import com.toadzip.backend.ingest.domain.LhNoticeProcessingStatus;
import com.toadzip.backend.ingest.dto.ExternalApiCollectionReport;
import com.toadzip.backend.ingest.dto.ExternalApiResponse;
import com.toadzip.backend.ingest.dto.LhNoticeRequest;
import com.toadzip.backend.ingest.repository.ExternalApiCollectionStore;
import com.toadzip.backend.ingest.repository.ExternalApiDataRepository;
import com.toadzip.backend.ingest.repository.LhNoticeApiRepository;
import com.toadzip.backend.ingest.repository.LhNoticeCollectionExecutionLock;
import com.toadzip.backend.ingest.repository.external.DataGoKrOpenApiClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
public class LhNoticeApiCollectionService {

    private static final String LIST_POINTER = "/response/body/item";

    private final Clock clock;

    private final ObjectMapper objectMapper;

    private final ExternalApiDataRepository apiDataRepository;

    private final LhNoticeApiRepository apiRepository;

    private final LhNoticeCollectionExecutionLock executionLock;

    private final ExternalApiCollectionStore store;

    private final ExternalApiFailureRecorder failureRecorder;

    private final LhSupplyInfoTypeCodeResolver supplyTypeCodeResolver;

    public LhNoticeApiCollectionService(
            Clock clock,
            ObjectMapper objectMapper,
            ExternalApiDataRepository apiDataRepository,
            LhNoticeApiRepository apiRepository,
            LhNoticeCollectionExecutionLock executionLock,
            ExternalApiCollectionStore store,
            ExternalApiFailureRecorder failureRecorder,
            LhSupplyInfoTypeCodeResolver supplyTypeCodeResolver
    ) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.apiDataRepository = apiDataRepository;
        this.apiRepository = apiRepository;
        this.executionLock = executionLock;
        this.store = store;
        this.failureRecorder = failureRecorder;
        this.supplyTypeCodeResolver = supplyTypeCodeResolver;
    }

    public ExternalApiCollectionReport collect(ExternalApi targetApi) {
        validateTargetApi(targetApi);
        return executionLock.tryRun(targetApi, () -> collectPendingApiData(targetApi))
                .orElseThrow(() -> {
                    log.warn(
                            "{} 수집이 이미 실행 중이므로 중복 실행을 건너뜁니다.",
                            operation(targetApi)
                    );
                    return new IngestAlreadyRunningException(
                            operation(targetApi) + " 수집이 이미 실행 중입니다."
                    );
                });
    }

    private ExternalApiCollectionReport collectPendingApiData(ExternalApi targetApi) {
        ExternalApiCollectionReport report = ExternalApiCollectionReport.empty(operation(targetApi));
        List<ExternalApiData> myHomeApiData = apiDataRepository
                .findAllPendingLhNoticeApiData(
                        ExternalApi.MYHOME_NOTICE,
                        LhNoticeProcessingStatus.PENDING
                );
        for (ExternalApiData apiData : myHomeApiData) {
            if (isAlreadyProcessedSnapshot(apiData)) {
                store.completeLhNoticeProcessing(apiData, clock.instant());
                continue;
            }
            CollectionResult result = collectNoticeRows(targetApi, apiData);
            report = report.plus(result.report());
            if (result.processingOutcome() == ProcessingOutcome.COMPLETED) {
                store.completeLhNoticeProcessing(apiData, clock.instant());
            }
            if (result.processingOutcome() == ProcessingOutcome.FAILED) {
                store.failLhNoticeProcessing(apiData, clock.instant());
            }
        }
        return report;
    }

    private boolean isAlreadyProcessedSnapshot(ExternalApiData apiData) {
        return apiDataRepository
                .existsByExternalApiAndRequestDescriptionAndContentHashAndLhNoticeProcessingStatus(
                        ExternalApi.MYHOME_NOTICE,
                        apiData.getRequestDescription(),
                        apiData.getContentHash(),
                        LhNoticeProcessingStatus.COMPLETED
                );
    }

    private CollectionResult collectNoticeRows(
            ExternalApi targetApi,
            ExternalApiData apiData
    ) {
        List<JsonNode> rows;
        try {
            rows = DataGoKrOpenApiClient.findRows(parse(apiData), LIST_POINTER);
        }
        catch (RuntimeException exception) {
            failureRecorder.record(
                    targetApi,
                    sourceDescription(apiData),
                    exception,
                    log,
                    "마이홈 공고 API 데이터 처리에 실패했습니다"
            );
            return CollectionResult.permanentFailure(operation(targetApi));
        }
        CollectionResult result = CollectionResult.empty(operation(targetApi));
        for (JsonNode row : rows) {
            result = result.plus(collectNotice(targetApi, row, apiData));
        }
        return result;
    }

    private CollectionResult collectNotice(
            ExternalApi targetApi,
            JsonNode row,
            ExternalApiData sourceApiData
    ) {
        LhNoticeRequest request;
        try {
            request = requestOf(row).orElseThrow(() ->
                    new IllegalStateException("LH 공고 조회 조건이 없습니다."));
        }
        catch (RuntimeException exception) {
            failureRecorder.record(
                    targetApi,
                    sourceDescription(sourceApiData),
                    exception,
                    log,
                    "LH 공고 조회 조건 생성에 실패했습니다"
            );
            return CollectionResult.permanentFailure(operation(targetApi));
        }
        CollectionResult result = collectApiData(targetApi, request);
        if (result.processingOutcome() != ProcessingOutcome.COMPLETED) {
            return result;
        }
        if (areAllNoticeApisCollected(request)) {
            return result;
        }
        return result.retryRequired();
    }

    private CollectionResult collectApiData(
            ExternalApi targetApi,
            LhNoticeRequest request
    ) {
        if (exists(targetApi, request)) {
            return CollectionResult.empty(operation(targetApi));
        }
        try {
            ExternalApiResponse response = fetch(targetApi, request);
            int storedApiDataCount = store.storeApiData(List.of(apiData(targetApi, request, response)));
            return CollectionResult.success(operation(targetApi), storedApiDataCount);
        }
        catch (RuntimeException exception) {
            failureRecorder.record(
                    targetApi,
                    request.requestDescription(),
                    exception,
                    log,
                    "LH 외부 API 수집에 실패했습니다"
            );
            return CollectionResult.retryableFailure(operation(targetApi));
        }
    }

    private boolean areAllNoticeApisCollected(LhNoticeRequest request) {
        return exists(ExternalApi.LH_NOTICE_DETAIL, request)
                && exists(ExternalApi.LH_NOTICE_SUPPLY, request);
    }

    private boolean exists(ExternalApi externalApi, LhNoticeRequest request) {
        return apiDataRepository.existsByExternalApiAndRequestDescriptionIn(
                externalApi,
                request.compatibleRequestDescriptions()
        );
    }

    private ExternalApiResponse fetch(ExternalApi targetApi, LhNoticeRequest request) {
        return switch (targetApi) {
            case LH_NOTICE_DETAIL -> apiRepository.fetchDetail(request);
            case LH_NOTICE_SUPPLY -> apiRepository.fetchSupply(request);
            default -> throw new IllegalArgumentException("LH 공고 API가 아닙니다.");
        };
    }

    private Optional<LhNoticeRequest> requestOf(JsonNode row) {
        String supplyTypeName = row.path("suplyTyNm").asString(null);
        Optional<String> supplyTypeCode = supplyTypeCodeResolver.resolve(supplyTypeName);
        if (supplyTypeCode.isEmpty()) {
            return Optional.empty();
        }
        String url = row.path("url").asString(null);
        if (url == null || url.isBlank()) {
            url = row.path("pcUrl").asString(null);
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

    private ExternalApiData apiData(
            ExternalApi targetApi,
            LhNoticeRequest request,
            ExternalApiResponse response
    ) {
        return ExternalApiData.create(
                targetApi,
                request.requestDescription(),
                1,
                clock.instant(),
                response.apiData()
        );
    }

    private JsonNode parse(ExternalApiData apiData) {
        try {
            return objectMapper.readTree(apiData.getApiData());
        }
        catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "마이홈 공고 외부 API 형식이 올바르지 않습니다.",
                    exception
            );
        }
    }

    private String sourceDescription(ExternalApiData apiData) {
        if (apiData.getId() == null) {
            return apiData.getRequestDescription();
        }
        return "myhomeApiDataId=" + apiData.getId();
    }

    private String operation(ExternalApi targetApi) {
        return switch (targetApi) {
            case LH_NOTICE_DETAIL -> "lh-notice-detail";
            case LH_NOTICE_SUPPLY -> "lh-notice-supply";
            default -> throw new IllegalArgumentException("LH 공고 API가 아닙니다.");
        };
    }

    private void validateTargetApi(ExternalApi targetApi) {
        if (targetApi != ExternalApi.LH_NOTICE_DETAIL
                && targetApi != ExternalApi.LH_NOTICE_SUPPLY) {
            throw new IllegalArgumentException("LH 공고 API가 아닙니다.");
        }
    }

    private enum ProcessingOutcome {
        COMPLETED,
        FAILED,
        RETRY_REQUIRED;

        private ProcessingOutcome plus(ProcessingOutcome other) {
            if (this == RETRY_REQUIRED || other == RETRY_REQUIRED) {
                return RETRY_REQUIRED;
            }
            if (this == FAILED || other == FAILED) {
                return FAILED;
            }
            return COMPLETED;
        }
    }

    private record CollectionResult(
            ExternalApiCollectionReport report,
            ProcessingOutcome processingOutcome
    ) {

        private static CollectionResult empty(String operation) {
            return new CollectionResult(
                    ExternalApiCollectionReport.empty(operation),
                    ProcessingOutcome.COMPLETED
            );
        }

        private static CollectionResult success(String operation, int storedApiDataCount) {
            return new CollectionResult(
                    new ExternalApiCollectionReport(operation, storedApiDataCount, 0),
                    ProcessingOutcome.COMPLETED
            );
        }

        private static CollectionResult permanentFailure(String operation) {
            return new CollectionResult(
                    new ExternalApiCollectionReport(operation, 0, 1),
                    ProcessingOutcome.FAILED
            );
        }

        private static CollectionResult retryableFailure(String operation) {
            return new CollectionResult(
                    new ExternalApiCollectionReport(operation, 0, 1),
                    ProcessingOutcome.RETRY_REQUIRED
            );
        }

        private CollectionResult retryRequired() {
            return new CollectionResult(report, ProcessingOutcome.RETRY_REQUIRED);
        }

        private CollectionResult plus(CollectionResult other) {
            return new CollectionResult(
                    report.plus(other.report()),
                    processingOutcome.plus(other.processingOutcome())
            );
        }
    }
}

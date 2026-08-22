package com.toadzip.backend.ingest.service;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.domain.ExternalApiData;
import com.toadzip.backend.ingest.domain.ExternalApi;
import com.toadzip.backend.ingest.dto.ExternalApiCollectionReport;
import com.toadzip.backend.ingest.dto.ExternalApiResponse;
import com.toadzip.backend.ingest.dto.LhNoticeRequest;
import com.toadzip.backend.ingest.repository.ExternalApiCollectionStore;
import com.toadzip.backend.ingest.repository.ExternalApiDataRepository;
import com.toadzip.backend.ingest.repository.LhNoticeApiRepository;
import com.toadzip.backend.ingest.repository.external.DataGoKrOpenApiClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
public class LhNoticeCollectionService {

    private static final String LIST_POINTER = "/response/body/item";

    private final Clock clock;

    private final ObjectMapper objectMapper;

    private final ExternalApiDataRepository apiDataRepository;

    private final LhNoticeApiRepository apiRepository;

    private final ExternalApiCollectionStore store;

    private final ExternalApiFailureRecorder failureRecorder;

    private final LhSupplyInfoTypeCodeResolver supplyTypeCodeResolver;

    public LhNoticeCollectionService(
            Clock clock,
            ObjectMapper objectMapper,
            ExternalApiDataRepository apiDataRepository,
            LhNoticeApiRepository apiRepository,
            ExternalApiCollectionStore store,
            ExternalApiFailureRecorder failureRecorder,
            LhSupplyInfoTypeCodeResolver supplyTypeCodeResolver
    ) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.apiDataRepository = apiDataRepository;
        this.apiRepository = apiRepository;
        this.store = store;
        this.failureRecorder = failureRecorder;
        this.supplyTypeCodeResolver = supplyTypeCodeResolver;
    }

    public ExternalApiCollectionReport collect() {
        ExternalApiCollectionReport report = ExternalApiCollectionReport.empty("lh-notice");
        List<ExternalApiData> myHomeApiData = apiDataRepository
                .findAllByExternalApiOrderByCollectedAtAscIdAsc(ExternalApi.MYHOME_NOTICE);
        for (ExternalApiData apiData : myHomeApiData) {
            report = report.plus(collectNoticeRows(apiData));
        }
        return report;
    }

    private ExternalApiCollectionReport collectNoticeRows(ExternalApiData apiData) {
        List<JsonNode> rows = DataGoKrOpenApiClient.findRows(parse(apiData), LIST_POINTER);
        ExternalApiCollectionReport report = ExternalApiCollectionReport.empty("lh-notice");
        for (JsonNode row : rows) {
            report = report.plus(collectNotice(row, apiData.getId()));
        }
        return report;
    }

    private ExternalApiCollectionReport collectNotice(JsonNode row, Long apiDataId) {
        String requestDescription = "myhomeApiDataId=" + apiDataId;
        try {
            LhNoticeRequest request = requestOf(row).orElseThrow(() ->
                    new IllegalStateException("LH 공고 상세 조회 조건이 없습니다."));
            requestDescription = request.requestDescription();
            ExternalApiResponse detail = apiRepository.fetchDetail(request);
            ExternalApiResponse supply = apiRepository.fetchSupply(request);
            List<ExternalApiData> apiData = List.of(
                    apiData(ExternalApi.LH_NOTICE_DETAIL, request, detail),
                    apiData(ExternalApi.LH_NOTICE_SUPPLY, request, supply)
            );
            store.storeApiData(apiData);
            return new ExternalApiCollectionReport("lh-notice", apiData.size(), 0);
        }
        catch (RuntimeException exception) {
            failureRecorder.record(
                    ExternalApi.LH_NOTICE_DETAIL,
                    requestDescription,
                    exception,
                    log,
                    "LH 공고 상세·공급 외부 API 수집에 실패했습니다"
            );
            return new ExternalApiCollectionReport("lh-notice", 0, 1);
        }
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
            ExternalApi externalApi,
            LhNoticeRequest request,
            ExternalApiResponse response
    ) {
        return ExternalApiData.create(
                externalApi,
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
            throw new IllegalStateException("마이홈 공고 외부 API 형식이 올바르지 않습니다.", exception);
        }
    }
}

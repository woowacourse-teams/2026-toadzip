package com.toadzip.backend.ingest.service;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.domain.ExternalDataSnapshot;
import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.dto.LhNoticeRequest;
import com.toadzip.backend.ingest.repository.ExternalDataCollectionStore;
import com.toadzip.backend.ingest.repository.ExternalDataSnapshotRepository;
import com.toadzip.backend.ingest.repository.LhNoticeExternalRepository;
import com.toadzip.backend.ingest.repository.external.DataGoKrOpenApiClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
public class LhNoticeCollectionService {

    private static final String LIST_POINTER = "/response/body/item";

    private final Clock clock;

    private final ObjectMapper objectMapper;

    private final ExternalDataSnapshotRepository snapshotRepository;

    private final LhNoticeExternalRepository externalRepository;

    private final ExternalDataCollectionStore store;

    private final ExternalDataFailureRecorder failureRecorder;

    private final LhSupplyInfoTypeCodeResolver supplyTypeCodeResolver;

    public LhNoticeCollectionService(
            Clock clock,
            ObjectMapper objectMapper,
            ExternalDataSnapshotRepository snapshotRepository,
            LhNoticeExternalRepository externalRepository,
            ExternalDataCollectionStore store,
            ExternalDataFailureRecorder failureRecorder,
            LhSupplyInfoTypeCodeResolver supplyTypeCodeResolver
    ) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.snapshotRepository = snapshotRepository;
        this.externalRepository = externalRepository;
        this.store = store;
        this.failureRecorder = failureRecorder;
        this.supplyTypeCodeResolver = supplyTypeCodeResolver;
    }

    public ExternalDataCollectionReport collect() {
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty("lh-notice");
        List<ExternalDataSnapshot> myHomeSnapshots = snapshotRepository
                .findAllBySourceOrderByCollectedAtAscIdAsc(ExternalDataSource.MYHOME_NOTICE);
        for (ExternalDataSnapshot snapshot : myHomeSnapshots) {
            report = report.plus(collectNoticeRows(snapshot));
        }
        return report;
    }

    private ExternalDataCollectionReport collectNoticeRows(ExternalDataSnapshot snapshot) {
        List<JsonNode> rows = DataGoKrOpenApiClient.findRows(parse(snapshot), LIST_POINTER);
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty("lh-notice");
        for (JsonNode row : rows) {
            report = report.plus(collectNotice(row, snapshot.getId()));
        }
        return report;
    }

    private ExternalDataCollectionReport collectNotice(JsonNode row, Long sourceSnapshotId) {
        String requestDescription = "myhomeSnapshotId=" + sourceSnapshotId;
        try {
            LhNoticeRequest request = requestOf(row).orElseThrow(() ->
                    new IllegalStateException("LH 공고 상세 조회 조건이 없습니다."));
            requestDescription = request.requestDescription();
            ExternalDataResponse detail = externalRepository.fetchDetail(request);
            ExternalDataResponse supply = externalRepository.fetchSupply(request);
            List<ExternalDataSnapshot> snapshots = List.of(
                    snapshot(ExternalDataSource.LH_NOTICE_DETAIL, request, detail),
                    snapshot(ExternalDataSource.LH_NOTICE_SUPPLY, request, supply)
            );
            store.storeSnapshots(snapshots);
            return new ExternalDataCollectionReport("lh-notice", snapshots.size(), 0);
        }
        catch (RuntimeException exception) {
            failureRecorder.record(
                    ExternalDataSource.LH_NOTICE_DETAIL,
                    requestDescription,
                    exception,
                    log,
                    "LH 공고 상세·공급 원천 수집에 실패했습니다"
            );
            return new ExternalDataCollectionReport("lh-notice", 0, 1);
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

    private ExternalDataSnapshot snapshot(
            ExternalDataSource source,
            LhNoticeRequest request,
            ExternalDataResponse response
    ) {
        return ExternalDataSnapshot.create(
                source,
                request.requestDescription(),
                1,
                clock.instant(),
                response.rawPayload()
        );
    }

    private JsonNode parse(ExternalDataSnapshot snapshot) {
        try {
            return objectMapper.readTree(snapshot.getRawPayload());
        }
        catch (RuntimeException exception) {
            throw new IllegalStateException("마이홈 공고 원천 형식이 올바르지 않습니다.", exception);
        }
    }
}

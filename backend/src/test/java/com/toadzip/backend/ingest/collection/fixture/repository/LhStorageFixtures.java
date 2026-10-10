package com.toadzip.backend.ingest.collection.fixture.repository;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.projection.LhCatalogSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.dto.LhLeaseCatalogCollectedResponse;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.service.LhLeaseCatalogStorageService;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementStorageService;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceSnapshot;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 정제 통합 테스트에서도 운영 Storage와 수집 기록을 통해 원천을 준비한다. */
@Component
@Profile("test")
@RequiredArgsConstructor
public class LhStorageFixtures {

    private final LhAnnouncementStorageService storage;
    private final LhLeaseCatalogStorageService leaseStorage;
    private final SourceCollectionRecordService records;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public int replaceCatalog(List<LhCatalogSourceSnapshot> snapshots) {
        var at = clock.instant();
        var request = new LhLeaseCatalogCollectionRequest(null, 9999, 1000, at);
        var rows = snapshots.stream().map(row -> mapper.convertValue(row,
                com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSourceSnapshot.class)).toList();
        leaseStorage.complete(records.start(request), request,
                new LhLeaseCatalogCollectedResponse(rows.size(), at, rows));
        return rows.size();
    }

    public int replaceDetails(String panId, String description, List<LhAnnouncementDetailSource> sources) {
        requirePanId(panId, sources.stream().map(LhAnnouncementDetailSource::getPanId).toList());
        var request = request(CollectionSource.LH_ANNOUNCEMENT_DETAIL, description);
        var rows = sources.stream().map(row -> mapper.convertValue(row,
                LhAnnouncementDetailSourceSnapshot.class)).toList();
        storage.completeDetail(records.start(request), request, request.startedAt(), rows);
        return rows.size();
    }

    public int replaceSupplies(String panId, String description, List<LhAnnouncementSupplySource> sources) {
        requirePanId(panId, sources.stream().map(LhAnnouncementSupplySource::getPanId).toList());
        var request = request(CollectionSource.LH_ANNOUNCEMENT_SUPPLY, description);
        var rows = sources.stream().map(row -> mapper.convertValue(row,
                LhAnnouncementSupplySourceSnapshot.class)).toList();
        storage.completeSupply(records.start(request), request, request.startedAt(), rows);
        return rows.size();
    }

    private LhAnnouncementCollectionRequest request(CollectionSource source, String description) {
        Map<String, String> parameters = Arrays.stream(description.split("&"))
                .map(parameter -> parameter.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> pair[1]));
        var query = new LhAnnouncementQuery(parameters.get("PAN_ID"), parameters.get("CCR_CNNT_SYS_DS_CD"),
                parameters.get("UPP_AIS_TP_CD"), parameters.get("AIS_TP_CD"), parameters.get("SPL_INF_TP_CD"));
        return new LhAnnouncementCollectionRequest(null, source, query,
                Integer.parseInt(parameters.getOrDefault("COLLECTION_VERSION", "6")), clock.instant());
    }

    private void requirePanId(String requested, List<String> identifiers) {
        if (identifiers.stream().anyMatch(identifier -> !requested.equals(identifier))) {
            throw new IllegalArgumentException("LH 원천 행의 공고 식별자가 조회 조건과 다릅니다.");
        }
    }
}

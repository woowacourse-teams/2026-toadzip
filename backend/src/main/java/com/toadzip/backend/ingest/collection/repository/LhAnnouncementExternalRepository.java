package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.repository.external.DataGoKrOpenApiClient;
import com.toadzip.backend.ingest.collection.repository.external.LhAnnouncementCircuitBreaker;
import com.toadzip.backend.ingest.collection.repository.external.LhAnnouncementResponseIdentityValidator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;

@Repository
public class LhAnnouncementExternalRepository {

    private static final String DETAIL_PATH = "lhLeaseNoticeDtlInfo1/getLeaseNoticeDtlInfo1";

    private static final String SUPPLY_PATH = "lhLeaseNoticeSplInfo1/getLeaseNoticeSplInfo1";

    private final DataGoKrOpenApiClient client;
    private final LhAnnouncementCircuitBreaker circuitBreaker;
    private final LhAnnouncementResponseIdentityValidator identityValidator =
            new LhAnnouncementResponseIdentityValidator();

    public LhAnnouncementExternalRepository(
            @Qualifier("lhAnnouncementOpenApiClient") DataGoKrOpenApiClient client,
            LhAnnouncementCircuitBreaker circuitBreaker
    ) {
        this.client = client;
        this.circuitBreaker = circuitBreaker;
    }

    public ExternalDataResponse fetchDetail(LhAnnouncementRequest request) {
        return fetch(DETAIL_PATH, request);
    }

    public ExternalDataResponse fetchSupply(LhAnnouncementRequest request) {
        return fetch(SUPPLY_PATH, request);
    }

    public ExternalDataResponse fetchCatalog(int page, int pageSize) {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("PAGE", String.valueOf(page));
        params.add("PG_SZ", String.valueOf(pageSize));
        return circuitBreaker.execute(() -> client.get("lhLeaseNoticeInfo1/lhLeaseNoticeInfo1", params));
    }

    private ExternalDataResponse fetch(String path, LhAnnouncementRequest request) {
        ExternalDataResponse response = circuitBreaker.execute(() -> client.get(path, request.toParams()));
        identityValidator.validate(request, response.body());
        return response;
    }
}

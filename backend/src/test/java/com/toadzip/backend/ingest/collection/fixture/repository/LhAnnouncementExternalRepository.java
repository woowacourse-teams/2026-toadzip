package com.toadzip.backend.ingest.collection.fixture.repository;

import com.toadzip.backend.ingest.collection.fixture.repository.external.LhAnnouncementResponseIdentityValidator;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.repository.external.LhAnnouncementCircuitBreaker;
import com.toadzip.backend.ingest.collection.repository.external.DataGoKrOpenApiClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.JsonNode;

@Repository
@Profile("test")
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

    public JsonNode fetchDetail(LhAnnouncementRequest request) {
        return fetch(DETAIL_PATH, request);
    }

    public JsonNode fetchSupply(LhAnnouncementRequest request) {
        return fetch(SUPPLY_PATH, request);
    }

    public JsonNode fetchCatalog(int page, int pageSize) {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("PAGE", String.valueOf(page));
        params.add("PG_SZ", String.valueOf(pageSize));
        return circuitBreaker.execute(() -> client.get("lhLeaseNoticeInfo1/lhLeaseNoticeInfo1", params));
    }

    private JsonNode fetch(String path, LhAnnouncementRequest request) {
        JsonNode response = circuitBreaker.execute(() -> client.get(path, request.toParams()));
        identityValidator.validate(request, response);
        return response;
    }
}

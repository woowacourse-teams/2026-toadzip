package com.toadzip.backend.ingest.collection.lh.repository;

import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
import com.toadzip.backend.ingest.collection.lh.repository.external.LhAnnouncementCircuitBreaker;
import com.toadzip.backend.ingest.collection.repository.external.DataGoKrOpenApiClient;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.JsonNode;

@Repository
public class LhAnnouncementQueryApiRepository {

    private final DataGoKrOpenApiClient client;

    private final LhAnnouncementCircuitBreaker circuitBreaker;

    public LhAnnouncementQueryApiRepository(
            @Qualifier("lhAnnouncementOpenApiClient") DataGoKrOpenApiClient client,
            LhAnnouncementCircuitBreaker circuitBreaker
    ) {
        this.client = client;
        this.circuitBreaker = circuitBreaker;
    }

    public JsonNode supply(LhAnnouncementQuery query) {
        return fetch("lhLeaseNoticeSplInfo1/getLeaseNoticeSplInfo1", query);
    }

    public JsonNode detail(LhAnnouncementQuery query) {
        return fetch("lhLeaseNoticeDtlInfo1/getLeaseNoticeDtlInfo1", query);
    }

    private JsonNode fetch(String path, LhAnnouncementQuery query) {
        var parameters = new LinkedMultiValueMap<String, String>();
        query.parameters().forEach(parameters::add);
        JsonNode response = circuitBreaker.execute(() -> client.get(path, parameters));
        JsonNode header = LhResponseDatasetReader.require(response, "resHeader");
        JsonNode conditions = LhResponseDatasetReader.require(response, "dsSch");
        if (header.size() != 1 || !"Y".equals(LhResponseDatasetReader.text(header.get(0), "SS_CODE"))
                || conditions.size() != 1) {
            throw new ExternalDataRequestException("LH 공고 응답의 헤더 또는 조회 조건이 올바르지 않습니다.");
        }
        for (var condition : query.parameters().entrySet()) {
            if (!condition.getValue().equals(LhResponseDatasetReader.text(conditions.get(0), condition.getKey()))) {
                throw new ExternalDataRequestException("LH 공고 응답의 조회 조건이 요청과 다릅니다: " + condition.getKey());
            }
        }
        return response;
    }
}

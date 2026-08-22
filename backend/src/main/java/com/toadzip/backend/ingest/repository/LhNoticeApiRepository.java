package com.toadzip.backend.ingest.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import com.toadzip.backend.ingest.dto.ExternalApiResponse;
import com.toadzip.backend.ingest.dto.LhNoticeRequest;
import com.toadzip.backend.ingest.repository.external.DataGoKrOpenApiClient;

@Repository
public class LhNoticeApiRepository {

    private static final String DETAIL_PATH = "lhLeaseNoticeDtlInfo1/getLeaseNoticeDtlInfo1";

    private static final String SUPPLY_PATH = "lhLeaseNoticeSplInfo1/getLeaseNoticeSplInfo1";

    private final DataGoKrOpenApiClient client;

    public LhNoticeApiRepository(@Qualifier("lhOpenApiClient") DataGoKrOpenApiClient client) {
        this.client = client;
    }

    public ExternalApiResponse fetchDetail(LhNoticeRequest request) {
        return client.get(DETAIL_PATH, request.toParams());
    }

    public ExternalApiResponse fetchSupply(LhNoticeRequest request) {
        return client.get(SUPPLY_PATH, request.toParams());
    }
}

package com.toadzip.backend.ingest.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import com.toadzip.backend.ingest.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.dto.LhNoticeRequest;
import com.toadzip.backend.ingest.repository.external.DataGoKrOpenApiClient;

@Repository
public class LhNoticeExternalRepository {

    private static final String DETAIL_PATH = "lhLeaseNoticeDtlInfo1/getLeaseNoticeDtlInfo1";

    private static final String SUPPLY_PATH = "lhLeaseNoticeSplInfo1/getLeaseNoticeSplInfo1";

    private final DataGoKrOpenApiClient client;

    public LhNoticeExternalRepository(@Qualifier("lhOpenApiClient") DataGoKrOpenApiClient client) {
        this.client = client;
    }

    public ExternalDataResponse fetchDetail(LhNoticeRequest request) {
        return client.get(DETAIL_PATH, request.toParams());
    }

    public ExternalDataResponse fetchSupply(LhNoticeRequest request) {
        return client.get(SUPPLY_PATH, request.toParams());
    }
}

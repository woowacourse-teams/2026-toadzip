package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.repository.external.DataGoKrOpenApiClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

@Repository
public class LhAnnouncementExternalRepository {

    private static final String DETAIL_PATH = "lhLeaseNoticeDtlInfo1/getLeaseNoticeDtlInfo1";

    private static final String SUPPLY_PATH = "lhLeaseNoticeSplInfo1/getLeaseNoticeSplInfo1";

    private final DataGoKrOpenApiClient client;

    public LhAnnouncementExternalRepository(@Qualifier("lhOpenApiClient") DataGoKrOpenApiClient client) {
        this.client = client;
    }

    public ExternalDataResponse fetchDetail(LhAnnouncementRequest request) {
        return client.get(DETAIL_PATH, request.toParams());
    }

    public ExternalDataResponse fetchSupply(LhAnnouncementRequest request) {
        return client.get(SUPPLY_PATH, request.toParams());
    }
}

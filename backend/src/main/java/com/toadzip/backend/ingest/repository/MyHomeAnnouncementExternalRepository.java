package com.toadzip.backend.ingest.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.toadzip.backend.ingest.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementSupplyType;
import com.toadzip.backend.ingest.repository.external.DataGoKrOpenApiClient;

@Repository
public class MyHomeAnnouncementExternalRepository {

    private static final String PATH = "rsdtRcritNtcList";

    private final DataGoKrOpenApiClient client;

    public MyHomeAnnouncementExternalRepository(
            @Qualifier("myHomeAnnouncementOpenApiClient") DataGoKrOpenApiClient client
    ) {
        this.client = client;
    }

    public ExternalDataResponse fetch(
            MyHomeAnnouncementSupplyType supplyType,
            MyHomeAnnouncementCollectionRequest request,
            int page
    ) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("suplyTy", supplyType.requestCode());
        params.add("pageNo", String.valueOf(page));
        params.add("numOfRows", String.valueOf(request.pageSize()));
        return client.get(PATH, params);
    }
}

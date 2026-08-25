package com.toadzip.backend.ingest.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.toadzip.backend.ingest.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.dto.MyHomeNoticeCollectionRequest;
import com.toadzip.backend.ingest.dto.MyHomeNoticeSupplyType;
import com.toadzip.backend.ingest.repository.external.DataGoKrOpenApiClient;

@Repository
public class MyHomeNoticeExternalRepository {

    private static final String PATH = "rsdtRcritNtcList";

    private final DataGoKrOpenApiClient client;

    public MyHomeNoticeExternalRepository(
            @Qualifier("myHomeNoticeOpenApiClient") DataGoKrOpenApiClient client
    ) {
        this.client = client;
    }

    public ExternalDataResponse fetch(
            MyHomeNoticeSupplyType supplyType,
            MyHomeNoticeCollectionRequest request,
            int page
    ) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("suplyTy", supplyType.requestCode());
        params.add("pageNo", String.valueOf(page));
        params.add("numOfRows", String.valueOf(request.pageSize()));
        return client.get(PATH, params);
    }
}

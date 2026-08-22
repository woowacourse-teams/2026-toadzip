package com.toadzip.backend.ingest.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.toadzip.backend.ingest.dto.ExternalApiResponse;
import com.toadzip.backend.ingest.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.dto.MyHomeRegion;
import com.toadzip.backend.ingest.repository.external.DataGoKrOpenApiClient;

@Repository
public class MyHomeComplexApiRepository {

    private static final String PATH = "rentalHouseGwList";

    private final DataGoKrOpenApiClient client;

    public MyHomeComplexApiRepository(
            @Qualifier("myHomeComplexOpenApiClient") DataGoKrOpenApiClient client
    ) {
        this.client = client;
    }

    public ExternalApiResponse fetch(MyHomeRegion region, MyHomeComplexCollectionRequest request, int page) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("brtcCode", region.provinceCode());
        params.add("signguCode", region.districtCode());
        params.add("pageNo", String.valueOf(page));
        params.add("numOfRows", String.valueOf(request.pageSize()));
        return client.get(PATH, params);
    }
}

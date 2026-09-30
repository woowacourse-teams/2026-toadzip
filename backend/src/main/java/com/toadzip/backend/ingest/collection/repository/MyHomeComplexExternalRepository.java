package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.collection.dto.MyHomeRegion;
import com.toadzip.backend.ingest.collection.repository.external.DataGoKrOpenApiClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@Repository
public class MyHomeComplexExternalRepository {

    private static final String PATH = "rentalHouseGwList";

    private final DataGoKrOpenApiClient client;

    public MyHomeComplexExternalRepository(
            @Qualifier("myHomeComplexOpenApiClient") DataGoKrOpenApiClient client
    ) {
        this.client = client;
    }

    public ExternalDataResponse fetch(MyHomeRegion region, MyHomeComplexCollectionRequest request, int page) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("brtcCode", region.provinceCode());
        params.add("signguCode", region.districtCode());
        params.add("pageNo", String.valueOf(page));
        params.add("numOfRows", String.valueOf(request.pageSize()));
        return client.get(PATH, params);
    }
}

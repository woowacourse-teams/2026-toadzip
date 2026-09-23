package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.repository.external.DataGoKrOpenApiClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@Repository
public class LhLeaseCatalogExternalRepository {

    private static final String PATH = "lhLeaseInfo1/lhLeaseInfo1";

    private final DataGoKrOpenApiClient client;

    public LhLeaseCatalogExternalRepository(@Qualifier("lhOpenApiClient") DataGoKrOpenApiClient client) {
        this.client = client;
    }

    public ExternalDataResponse fetch(LhLeaseCatalogCollectionRequest request, int page) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("PG_SZ", String.valueOf(request.pageSize()));
        params.add("PAGE", String.valueOf(page));
        return client.get(PATH, params);
    }
}

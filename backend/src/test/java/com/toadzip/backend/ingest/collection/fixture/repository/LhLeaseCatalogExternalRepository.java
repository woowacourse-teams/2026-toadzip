package com.toadzip.backend.ingest.collection.fixture.repository;

import com.toadzip.backend.ingest.collection.lh.leasecatalog.dto.api.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.repository.external.DataGoKrOpenApiClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

@Repository
@Profile("test")
public class LhLeaseCatalogExternalRepository {

    private static final String PATH = "lhLeaseInfo1/lhLeaseInfo1";

    private final DataGoKrOpenApiClient client;

    public LhLeaseCatalogExternalRepository(@Qualifier("lhOpenApiClient") DataGoKrOpenApiClient client) {
        this.client = client;
    }

    public JsonNode fetch(LhLeaseCatalogCollectionRequest request, int page) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("PG_SZ", String.valueOf(request.pageSize()));
        params.add("PAGE", String.valueOf(page));
        return client.get(PATH, params);
    }
}

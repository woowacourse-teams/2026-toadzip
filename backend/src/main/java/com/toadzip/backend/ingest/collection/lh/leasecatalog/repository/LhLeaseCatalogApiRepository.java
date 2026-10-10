package com.toadzip.backend.ingest.collection.lh.leasecatalog.repository;

import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import com.toadzip.backend.ingest.collection.repository.external.DataGoKrOpenApiClient;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;

@Repository
public class LhLeaseCatalogApiRepository {

    private final DataGoKrOpenApiClient client;
    private final LhLeaseCatalogPageParser parser;

    public LhLeaseCatalogApiRepository(
            @Qualifier("lhOpenApiClient") DataGoKrOpenApiClient client, LhLeaseCatalogPageParser parser
    ) {
        this.client = client;
        this.parser = parser;
    }

    public SourcePage<LhCatalogSourceSnapshot> fetch(LhLeaseCatalogCollectionRequest request, int page) {
        var parameters = new LinkedMultiValueMap<String, String>();
        parameters.add("PG_SZ", Integer.toString(request.pageSize()));
        parameters.add("PAGE", Integer.toString(page));
        try {
            return parser.parse(client.get("lhLeaseInfo1/lhLeaseInfo1", parameters), request, page);
        } catch (ExternalDataRequestException failure) {
            throw failure.withContext("PG_SZ=%d&PAGE=%d".formatted(request.pageSize(), page));
        }
    }
}

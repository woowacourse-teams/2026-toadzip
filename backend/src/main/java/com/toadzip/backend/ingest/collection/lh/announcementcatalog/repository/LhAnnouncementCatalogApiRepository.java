package com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository;

import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogPage;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.dto.LhAnnouncementCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.repository.external.LhAnnouncementCircuitBreaker;
import com.toadzip.backend.ingest.collection.repository.external.DataGoKrOpenApiClient;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;

@Repository
public class LhAnnouncementCatalogApiRepository {

    private final DataGoKrOpenApiClient client;

    private final LhAnnouncementCatalogPageParser parser;
    private final LhAnnouncementCircuitBreaker circuitBreaker;

    public LhAnnouncementCatalogApiRepository(
            @Qualifier("lhAnnouncementOpenApiClient") DataGoKrOpenApiClient client,
            LhAnnouncementCatalogPageParser parser,
            LhAnnouncementCircuitBreaker circuitBreaker
    ) {
        this.client = client;
        this.parser = parser;
        this.circuitBreaker = circuitBreaker;
    }

    public LhAnnouncementCatalogPage fetch(LhAnnouncementCatalogCollectionRequest request, int page) {
        var parameters = new LinkedMultiValueMap<String, String>();
        parameters.add("PG_SZ", Integer.toString(request.pageSize()));
        parameters.add("PAGE", Integer.toString(page));
        try {
            var response = circuitBreaker.execute(
                    () -> client.get("lhLeaseNoticeInfo1/lhLeaseNoticeInfo1", parameters));
            return parser.parse(response, page, request.pageSize());
        } catch (ExternalDataRequestException failure) {
            throw failure.withContext("PG_SZ=" + request.pageSize() + "&PAGE=" + page);
        }
    }
}

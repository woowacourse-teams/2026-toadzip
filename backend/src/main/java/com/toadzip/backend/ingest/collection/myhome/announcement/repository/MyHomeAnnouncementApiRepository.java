package com.toadzip.backend.ingest.collection.myhome.announcement.repository;

import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import com.toadzip.backend.ingest.collection.repository.external.DataGoKrOpenApiClient;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;

@Repository
public class MyHomeAnnouncementApiRepository {

    private final DataGoKrOpenApiClient client;
    private final MyHomeAnnouncementPageParser parser;

    public MyHomeAnnouncementApiRepository(
            @Qualifier("myHomeAnnouncementOpenApiClient") DataGoKrOpenApiClient client,
            MyHomeAnnouncementPageParser parser
    ) {
        this.client = client;
        this.parser = parser;
    }

    public SourcePage<MyHomeAnnouncementSourceSnapshot> fetch(MyHomeAnnouncementCollectionRequest request, int page) {
        var parameters = new LinkedMultiValueMap<String, String>();
        parameters.add("suplyTy", request.supplyTypeCode());
        parameters.add("pageNo", Integer.toString(page));
        parameters.add("numOfRows", Integer.toString(request.pageSize()));
        try {
            return parser.parse(client.get("rsdtRcritNtcList", parameters));
        } catch (ExternalDataRequestException failure) {
            throw contextualFailure(request, page, failure);
        }
    }

    private ExternalDataRequestException contextualFailure(
            MyHomeAnnouncementCollectionRequest request, int page, ExternalDataRequestException failure
    ) {
        String reason = "suplyTy=%s&pageNo=%d: %s".formatted(request.supplyTypeCode(), page, failure.getMessage());
        if (failure.isRateLimited()) {
            return ExternalDataRequestException.rateLimited(reason, failure, failure.isRetryable());
        }
        if (failure.isRetryable()) {
            return ExternalDataRequestException.retryable(reason, failure);
        }
        return new ExternalDataRequestException(reason, failure);
    }
}

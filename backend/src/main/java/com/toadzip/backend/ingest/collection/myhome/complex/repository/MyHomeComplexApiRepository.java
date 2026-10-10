package com.toadzip.backend.ingest.collection.myhome.complex.repository;

import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import com.toadzip.backend.ingest.collection.repository.external.DataGoKrOpenApiClient;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;

@Repository
public class MyHomeComplexApiRepository {

    private final DataGoKrOpenApiClient client;
    private final MyHomeComplexPageParser parser;

    public MyHomeComplexApiRepository(
            @Qualifier("myHomeComplexOpenApiClient") DataGoKrOpenApiClient client,
            MyHomeComplexPageParser parser
    ) {
        this.client = client;
        this.parser = parser;
    }

    public SourcePage<MyHomeComplexSourceSnapshot> fetch(MyHomeComplexCollectionRequest request, int page) {
        var parameters = new LinkedMultiValueMap<String, String>();
        parameters.add("brtcCode", request.provinceCode());
        parameters.add("signguCode", request.districtCode());
        parameters.add("pageNo", Integer.toString(page));
        parameters.add("numOfRows", Integer.toString(request.pageSize()));
        try {
            return parser.parse(client.get("rentalHouseGwList", parameters));
        } catch (ExternalDataRequestException failure) {
            throw contextualFailure(request, page, failure);
        }
    }

    private ExternalDataRequestException contextualFailure(
            MyHomeComplexCollectionRequest request, int page, ExternalDataRequestException failure
    ) {
        String reason = "brtcCode=%s&signguCode=%s&pageNo=%d: %s".formatted(
                request.provinceCode(), request.districtCode(), page, failure.getMessage());
        if (failure.isRateLimited()) {
            return ExternalDataRequestException.rateLimited(reason, failure, failure.isRetryable());
        }
        if (failure.isRetryable()) {
            return ExternalDataRequestException.retryable(reason, failure);
        }
        return new ExternalDataRequestException(reason, failure);
    }
}

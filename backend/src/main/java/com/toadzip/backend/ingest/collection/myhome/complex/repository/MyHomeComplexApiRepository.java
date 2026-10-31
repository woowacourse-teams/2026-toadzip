package com.toadzip.backend.ingest.collection.myhome.complex.repository;

import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.collection.myhome.repository.MyHomePageParser;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import com.toadzip.backend.ingest.collection.repository.external.DataGoKrOpenApiClient;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MyHomeComplexApiRepository {

    private final DataGoKrOpenApiClient client;
    private final MyHomePageParser<MyHomeComplexSourceSnapshot> parser;

    public MyHomeComplexApiRepository(
            @Qualifier("myHomeComplexOpenApiClient") DataGoKrOpenApiClient client,
            ObjectMapper objectMapper
    ) {
        this.client = client;
        this.parser = new MyHomePageParser<>(objectMapper, MyHomeComplexSourceSnapshot.class, "단지");
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
            throw failure.withContext("brtcCode=%s&signguCode=%s&pageNo=%d".formatted(
                    request.provinceCode(), request.districtCode(), page));
        }
    }
}

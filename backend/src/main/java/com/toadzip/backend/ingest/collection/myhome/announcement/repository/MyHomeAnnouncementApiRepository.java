package com.toadzip.backend.ingest.collection.myhome.announcement.repository;

import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.myhome.repository.MyHomePageParser;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import com.toadzip.backend.ingest.collection.repository.external.DataGoKrOpenApiClient;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MyHomeAnnouncementApiRepository {

    private final DataGoKrOpenApiClient client;
    private final MyHomePageParser<MyHomeAnnouncementSourceSnapshot> parser;

    public MyHomeAnnouncementApiRepository(
            @Qualifier("myHomeAnnouncementOpenApiClient") DataGoKrOpenApiClient client,
            ObjectMapper objectMapper
    ) {
        this.client = client;
        this.parser = new MyHomePageParser<>(objectMapper, MyHomeAnnouncementSourceSnapshot.class, "공고");
    }

    public SourcePage<MyHomeAnnouncementSourceSnapshot> fetch(MyHomeAnnouncementCollectionRequest request, int page) {
        var parameters = new LinkedMultiValueMap<String, String>();
        parameters.add("suplyTy", request.supplyTypeCode());
        parameters.add("pageNo", Integer.toString(page));
        parameters.add("numOfRows", Integer.toString(request.pageSize()));
        try {
            return parser.parse(client.get("rsdtRcritNtcList", parameters));
        } catch (ExternalDataRequestException failure) {
            throw failure.withContext("suplyTy=%s&pageNo=%d".formatted(request.supplyTypeCode(), page));
        }
    }
}

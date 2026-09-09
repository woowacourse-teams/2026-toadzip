package com.toadzip.backend.ingest.collection.repository.external;

import com.toadzip.backend.ingest.collection.dto.ExternalDataPage;
import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.dto.MyHomeAnnouncementSourceItem;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class MyHomeAnnouncementResponseParser {

    private static final String LIST_POINTER = "/response/body/item";

    private final ObjectMapper objectMapper;

    public ExternalDataPage<MyHomeAnnouncementSourceItem> parse(ExternalDataResponse response) {
        List<MyHomeAnnouncementSourceItem> items = ExternalResponseRows.at(response.body(), LIST_POINTER)
                .stream()
                .map(this::sourceItemOf)
                .toList();
        int totalCount = response.body().at("/response/body/totalCount").asInt(-1);
        return new ExternalDataPage<>(items, totalCount);
    }

    private MyHomeAnnouncementSourceItem sourceItemOf(JsonNode row) {
        try {
            return objectMapper.convertValue(row, MyHomeAnnouncementSourceItem.class);
        }
        catch (RuntimeException exception) {
            throw new ExternalDataRequestException("마이홈 공고 응답 항목 형식이 올바르지 않습니다.", exception);
        }
    }

}

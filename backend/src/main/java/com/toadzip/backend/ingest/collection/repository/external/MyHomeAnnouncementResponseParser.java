package com.toadzip.backend.ingest.collection.repository.external;

import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.dto.ExternalDataPage;
import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
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

    public ExternalDataPage<MyHomeAnnouncementSourceSnapshot> parse(ExternalDataResponse response) {
        List<MyHomeAnnouncementSourceSnapshot> snapshots = ExternalResponseRows.at(response.body(), LIST_POINTER)
                .stream()
                .map(this::sourceSnapshotOf)
                .toList();
        int totalCount = response.body().at("/response/body/totalCount").asInt(-1);
        return new ExternalDataPage<>(snapshots, totalCount);
    }

    private MyHomeAnnouncementSourceSnapshot sourceSnapshotOf(JsonNode row) {
        try {
            return objectMapper.convertValue(row, MyHomeAnnouncementSourceSnapshot.class);
        }
        catch (RuntimeException exception) {
            throw new ExternalDataRequestException("마이홈 공고 응답 항목 형식이 올바르지 않습니다.", exception);
        }
    }

}

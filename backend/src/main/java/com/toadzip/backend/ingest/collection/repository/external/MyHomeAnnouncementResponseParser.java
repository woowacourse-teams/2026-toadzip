package com.toadzip.backend.ingest.collection.repository.external;

import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.dto.ExternalDataPage;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class MyHomeAnnouncementResponseParser {

    private static final String LIST_POINTER = "/response/body/item";
    private static final String SUCCESS = "00";
    private static final String NO_DATA = "03";

    private final ObjectMapper objectMapper;

    public ExternalDataPage<MyHomeAnnouncementSourceSnapshot> parse(
            JsonNode root,
            int collectedCount
    ) {
        String resultCode = root.at("/response/header/resultCode").asString("");
        if (NO_DATA.equals(resultCode)) {
            return emptyPageOrThrow(collectedCount, 0);
        }
        if (!SUCCESS.equals(resultCode)) {
            throw invalidResponseSchema();
        }
        JsonNode body = root.at("/response/body");
        if (!body.isObject()) {
            throw invalidResponseSchema();
        }
        int totalCount = MyHomeResponseTotalCountParser.parse(body, this::invalidResponseSchema);
        JsonNode item = body.path("item");
        if (item.isMissingNode() || item.isNull()) {
            return emptyPageOrThrow(collectedCount, totalCount);
        }
        if (!item.isArray() && !item.isObject()) {
            throw invalidResponseSchema();
        }
        List<JsonNode> rows = ExternalResponseRows.at(root, LIST_POINTER);
        if (rows.isEmpty()) {
            return emptyPageOrThrow(collectedCount, totalCount);
        }
        if (collectedCount + rows.size() > totalCount) {
            throw invalidResponseSchema();
        }
        List<MyHomeAnnouncementSourceSnapshot> snapshots = rows
                .stream()
                .map(this::sourceSnapshotOf)
                .toList();
        return new ExternalDataPage<>(snapshots, totalCount);
    }

    private ExternalDataPage<MyHomeAnnouncementSourceSnapshot> emptyPageOrThrow(
            int collectedCount,
            int totalCount
    ) {
        if (collectedCount == 0 && totalCount == 0) {
            return new ExternalDataPage<>(List.of(), totalCount);
        }
        throw invalidResponseSchema();
    }

    private MyHomeAnnouncementSourceSnapshot sourceSnapshotOf(JsonNode row) {
        MyHomeAnnouncementSourceSnapshot snapshot;
        try {
            snapshot = objectMapper.convertValue(row, MyHomeAnnouncementSourceSnapshot.class);
        }
        catch (RuntimeException exception) {
            throw new ExternalDataRequestException(
                    "마이홈 공고 응답 항목 형식이 올바르지 않습니다.",
                    exception
            );
        }
        if (snapshot.pblancId() == null || snapshot.pblancId().isBlank()) {
            throw new ExternalDataRequestException("마이홈 공고 응답 항목에 공고 식별자가 없습니다.");
        }
        if (snapshot.houseSn() == null) {
            throw new ExternalDataRequestException("마이홈 공고 응답 항목에 주택 일련번호가 없습니다.");
        }
        return snapshot;
    }

    private ExternalDataRequestException invalidResponseSchema() {
        return new ExternalDataRequestException(
                "마이홈 공고 응답에 body, item 또는 totalCount 구조가 올바르지 않습니다."
        );
    }

}

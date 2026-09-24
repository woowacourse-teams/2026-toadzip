package com.toadzip.backend.ingest.collection.repository.external;

import com.toadzip.backend.ingest.collection.domain.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.collection.dto.ExternalDataPage;
import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class MyHomeComplexResponseParser {

    private static final String LIST_POINTER = "/response/body/item";

    private final ObjectMapper objectMapper;

    public ValidatedPage validate(ExternalDataResponse response, int collectedCount) {
        JsonNode root = response.body();
        String resultCode = root.at("/response/header/resultCode").asString("");
        if ("03".equals(resultCode)) {
            if (collectedCount > 0) {
                throw new ExternalDataRequestException("마이홈 단지 후속 페이지가 데이터 없음으로 응답했습니다.");
            }
            return new ValidatedPage(List.of(), -1);
        }
        if (!"00".equals(resultCode)) {
            throw new ExternalDataRequestException("마이홈 단지 응답 결과 코드가 올바르지 않습니다.");
        }
        JsonNode body = root.at("/response/body");
        if (!body.isObject()) {
            throw invalidResponseSchema();
        }
        int totalCount = totalCountOf(body);
        JsonNode item = body.path("item");
        if (item.isMissingNode() || item.isNull()) {
            return emptyPageOrThrow(totalCount, collectedCount);
        }
        if (!item.isArray() && !item.isObject()) {
            throw invalidResponseSchema();
        }
        List<JsonNode> rows = ExternalResponseRows.at(response.body(), LIST_POINTER);
        if (rows.isEmpty() && (totalCount < 0 ? collectedCount == 0 : collectedCount < totalCount)) {
            throw invalidResponseSchema();
        }
        if (totalCount >= 0 && collectedCount + rows.size() > totalCount) {
            throw invalidResponseSchema();
        }
        return new ValidatedPage(rows, totalCount);
    }

    public ExternalDataPage<MyHomeComplexSourceSnapshot> parseItems(ValidatedPage page) {
        List<MyHomeComplexSourceSnapshot> snapshots = page.rows().stream()
                .map(this::sourceSnapshotOf)
                .toList();
        return new ExternalDataPage<>(snapshots, page.totalCount());
    }

    private ValidatedPage emptyPageOrThrow(int totalCount, int collectedCount) {
        if (totalCount == 0 && collectedCount == 0) {
            return new ValidatedPage(List.of(), totalCount);
        }
        throw invalidResponseSchema();
    }

    private MyHomeComplexSourceSnapshot sourceSnapshotOf(JsonNode row) {
        MyHomeComplexSourceSnapshot snapshot;
        try {
            snapshot = objectMapper.convertValue(row, MyHomeComplexSourceSnapshot.class);
        }
        catch (RuntimeException exception) {
            throw new ExternalDataRequestException("마이홈 단지 응답 항목 형식이 올바르지 않습니다.", exception);
        }
        if (snapshot.hsmpSn() == null) {
            throw new ExternalDataRequestException("마이홈 단지 응답 항목에 단지 식별자가 없습니다.");
        }
        return snapshot;
    }

    private int totalCountOf(JsonNode body) {
        JsonNode totalCount = body.path("totalCount");
        if (totalCount.isMissingNode() || totalCount.isNull()) {
            return -1;
        }
        if (totalCount.isIntegralNumber() && totalCount.canConvertToInt()) {
            return requireNonNegativeTotalCount(totalCount.intValue());
        }
        if (totalCount.isTextual()) {
            return textualTotalCount(totalCount.textValue());
        }
        throw invalidResponseSchema();
    }

    private int textualTotalCount(String totalCount) {
        try {
            return requireNonNegativeTotalCount(Integer.parseInt(totalCount));
        }
        catch (NumberFormatException exception) {
            throw invalidResponseSchema();
        }
    }

    private int requireNonNegativeTotalCount(int totalCount) {
        if (totalCount < 0) {
            throw invalidResponseSchema();
        }
        return totalCount;
    }

    private ExternalDataRequestException invalidResponseSchema() {
        return new ExternalDataRequestException("마이홈 단지 응답에 body, item 또는 totalCount 구조가 올바르지 않습니다.");
    }

    public record ValidatedPage(List<JsonNode> rows, int totalCount) {
    }
}

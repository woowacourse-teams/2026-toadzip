package com.toadzip.backend.ingest.collection.repository.external;

import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.dto.ExternalDataPage;
import com.toadzip.backend.ingest.collection.dto.MyHomeComplexSourceItem;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class MyHomeComplexResponseParser {

    private static final String LIST_POINTER = "/response/body/item";

    private final ObjectMapper objectMapper;

    public MyHomeComplexResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ValidatedPage validate(ExternalDataResponse response, int collectedCount) {
        JsonNode root = response.body();
        String resultCode = root.at("/response/header/resultCode").asString("");
        if ("03".equals(resultCode)) {
            return new ValidatedPage(List.of(), -1);
        }
        JsonNode body = root.at("/response/body");
        if (!body.isObject()) {
            throw invalidResponseSchema();
        }
        int totalCount = totalCountOf(body);
        JsonNode item = body.path("item");
        if (item.isMissingNode() || item.isNull()) {
            return emptyPageOrThrow(totalCount);
        }
        if (!item.isArray() && !item.isObject()) {
            throw invalidResponseSchema();
        }
        List<JsonNode> rows = ExternalResponseRows.at(response.body(), LIST_POINTER);
        if (rows.isEmpty() && collectedCount == 0 && totalCount != 0) {
            throw invalidResponseSchema();
        }
        return new ValidatedPage(rows, totalCount);
    }

    public ExternalDataPage<MyHomeComplexSourceItem> parseItems(ValidatedPage page) {
        List<MyHomeComplexSourceItem> items = page.rows().stream()
                .map(this::sourceItemOf)
                .toList();
        return new ExternalDataPage<>(items, page.totalCount());
    }

    private ValidatedPage emptyPageOrThrow(int totalCount) {
        if (totalCount == 0) {
            return new ValidatedPage(List.of(), totalCount);
        }
        throw invalidResponseSchema();
    }

    private MyHomeComplexSourceItem sourceItemOf(JsonNode row) {
        try {
            return objectMapper.convertValue(row, MyHomeComplexSourceItem.class);
        }
        catch (RuntimeException exception) {
            throw new ExternalDataRequestException("마이홈 단지 응답 항목 형식이 올바르지 않습니다.", exception);
        }
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

package com.toadzip.backend.ingest.collection.myhome.complex.repository;

import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
public class MyHomeComplexPageParser {

    private final ObjectReader snapshotReader;

    public MyHomeComplexPageParser(ObjectMapper objectMapper) {
        snapshotReader = objectMapper.readerFor(MyHomeComplexSourceSnapshot.class)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    public SourcePage<MyHomeComplexSourceSnapshot> parse(JsonNode response) {
        String resultCode = response.at("/response/header/resultCode").asString("");
        JsonNode body = response.at("/response/body");
        if ("03".equals(resultCode)) {
            return noDataPage(body);
        }
        if (!"00".equals(resultCode) || !body.isObject()) {
            throw invalidSchema();
        }
        return new SourcePage<>(totalCount(body.path("totalCount")), rows(body.path("item")));
    }

    private SourcePage<MyHomeComplexSourceSnapshot> noDataPage(JsonNode body) {
        if (!body.isMissingNode() && !body.isNull() && !body.isObject()) {
            throw invalidSchema();
        }
        JsonNode count = body.path("totalCount");
        JsonNode item = body.path("item");
        if (!count.isMissingNode() && !count.isNull() && totalCount(count) != 0) {
            throw invalidSchema();
        }
        if (!item.isMissingNode() && !item.isNull() && !(item.isArray() && item.isEmpty())) {
            throw invalidSchema();
        }
        return new SourcePage<>(0, List.of());
    }

    private int totalCount(JsonNode node) {
        if (node.isIntegralNumber() && node.canConvertToInt() && node.asInt() >= 0) {
            return node.asInt();
        }
        if (node.isTextual()) {
            try {
                int value = Integer.parseInt(node.asString());
                if (value >= 0) {
                    return value;
                }
            } catch (NumberFormatException exception) {
                throw invalidSchema();
            }
        }
        throw invalidSchema();
    }

    private List<MyHomeComplexSourceSnapshot> rows(JsonNode item) {
        if (item.isMissingNode() || item.isNull()) {
            return List.of();
        }
        if (item.isObject()) {
            return List.of(snapshot(item));
        }
        if (!item.isArray()) {
            throw invalidSchema();
        }
        List<MyHomeComplexSourceSnapshot> rows = new ArrayList<>();
        for (JsonNode row : item) {
            rows.add(snapshot(row));
        }
        return rows;
    }

    private MyHomeComplexSourceSnapshot snapshot(JsonNode row) {
        if (!row.isObject()) {
            throw invalidSchema();
        }
        try {
            return snapshotReader.readValue(row);
        } catch (RuntimeException failure) {
            throw new ExternalDataRequestException("마이홈 단지 응답 행 형식이 올바르지 않습니다.", failure);
        }
    }

    private ExternalDataRequestException invalidSchema() {
        return new ExternalDataRequestException("마이홈 단지 응답의 body, item 또는 totalCount 구조가 올바르지 않습니다.");
    }
}

package com.toadzip.backend.ingest.collection.repository.external;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

final class ExternalResponseRows {

    private ExternalResponseRows() {
    }

    static List<JsonNode> at(JsonNode root, String pointer) {
        return rowsOf(root.at(pointer));
    }

    static List<JsonNode> find(JsonNode root, String key) {
        return rowsOf(findByKey(root, key));
    }

    static boolean contains(JsonNode root, String key) {
        return !findByKey(root, key).isMissingNode();
    }

    private static List<JsonNode> rowsOf(JsonNode found) {
        if (found.isMissingNode()) {
            return List.of();
        }
        if (found.isArray()) {
            List<JsonNode> rows = new ArrayList<>(found.size());
            for (JsonNode row : found) {
                if (!row.isObject()) {
                    throw new ExternalDataRequestException("외부 응답 dataset의 행은 객체여야 합니다.");
                }
                rows.add(row);
            }
            return rows;
        }
        if (found.isObject()) {
            return List.of(found);
        }
        throw new ExternalDataRequestException("외부 응답 dataset은 배열 또는 객체여야 합니다.");
    }

    private static JsonNode findByKey(JsonNode root, String key) {
        if (!root.isArray()) {
            return root.path(key);
        }
        for (JsonNode element : root) {
            JsonNode found = element.path(key);
            if (!found.isMissingNode()) {
                return found;
            }
        }
        return root.path(key);
    }
}

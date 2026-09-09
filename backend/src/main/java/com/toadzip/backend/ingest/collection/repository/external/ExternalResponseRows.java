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

    private static List<JsonNode> rowsOf(JsonNode found) {
        if (found.isArray()) {
            List<JsonNode> rows = new ArrayList<>(found.size());
            found.forEach(rows::add);
            return rows;
        }
        if (found.isObject()) {
            return List.of(found);
        }
        return List.of();
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

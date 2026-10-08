package com.toadzip.backend.ingest.collection.lh.repository;

import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import tools.jackson.databind.JsonNode;

public final class LhResponseDatasetReader {

    private LhResponseDatasetReader() {
    }

    public static String text(JsonNode row, String field) {
        JsonNode value = row.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isString() && !value.isNumber()) {
            throw new ExternalDataRequestException("LH 응답의 " + field + " 필드 형식이 올바르지 않습니다.");
        }
        return value.asString();
    }

    public static JsonNode require(JsonNode response, String name) {
        if (!response.isArray()) {
            throw invalid(name);
        }
        JsonNode dataset = null;
        for (JsonNode entry : response) {
            if (!entry.isObject()) {
                throw invalid(name);
            }
            if (!entry.has(name)) {
                continue;
            }
            if (dataset != null) {
                throw invalid(name);
            }
            dataset = entry.get(name);
        }
        if (dataset == null || !dataset.isArray()) {
            throw invalid(name);
        }
        for (JsonNode row : dataset) {
            if (!row.isObject()) {
                throw invalid(name);
            }
        }
        return dataset;
    }

    private static ExternalDataRequestException invalid(String name) {
        return new ExternalDataRequestException("LH 응답의 " + name + " dataset 구조가 올바르지 않습니다.");
    }
}

package com.toadzip.backend.eligibility.repository;

import com.toadzip.backend.eligibility.domain.RuleSetContent;
import com.toadzip.backend.eligibility.exception.InvalidEligibilityException;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

@Component
public class RuleSetJsonCodec {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_CONTENT_BYTES = 4 * 1024 * 1024;
    private final ObjectMapper json;

    public RuleSetJsonCodec(ObjectMapper json) {
        this.json = json;
    }

    public String snapshot(RuleSetContent content) {
        String encoded = json.writeValueAsString(new Snapshot(SCHEMA_VERSION, content));
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new InvalidEligibilityException("구조화 데이터는 4 MiB를 넘을 수 없습니다.");
        }
        return encoded;
    }

    public RuleSetContent read(String encoded) {
        Snapshot snapshot = json.readValue(encoded, Snapshot.class);
        if (snapshot.schemaVersion() != SCHEMA_VERSION || snapshot.content() == null) {
            throw new IllegalStateException("Unsupported eligibility snapshot schema");
        }
        return snapshot.content();
    }

    public <T> T convert(Object value, Class<T> type) {
        return json.convertValue(value, type);
    }

    private record Snapshot(int schemaVersion, RuleSetContent content) {}
}

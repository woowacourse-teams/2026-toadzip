package com.toadzip.backend.basiccomparison.repository;

import com.toadzip.backend.basiccomparison.domain.ComparisonContent;
import com.toadzip.backend.basiccomparison.domain.ComparisonProfile;
import com.toadzip.backend.basiccomparison.exception.InvalidComparisonException;

import jakarta.validation.Validator;

import org.springframework.stereotype.Component;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

@Component
public class ComparisonJsonCodec {
    private static final int MAX_CONTENT_BYTES = 4 * 1024 * 1024;
    private final ObjectMapper json;
    private final Validator validator;

    public ComparisonJsonCodec(ObjectMapper json, Validator validator) {
        this.json =
                json.rebuild()
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                        .build();
        this.validator = validator;
    }

    public String write(Object value) {
        String encoded = json.writeValueAsString(value);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new InvalidComparisonException("비교 데이터는 4 MiB를 넘을 수 없습니다.");
        }
        return encoded;
    }

    public ComparisonContent content(String encoded, int schemaVersion) {
        if (schemaVersion != 2 && schemaVersion != ComparisonContent.SCHEMA_VERSION) {
            throw new IllegalStateException("Unsupported basic comparison content schema");
        }
        return json.readValue(encoded, ComparisonContent.class);
    }

    public ComparisonProfile profile(String encoded, int inputContractVersion) {
        if (inputContractVersion != ComparisonProfile.CONTRACT_VERSION) {
            throw new IllegalStateException("Unsupported basic comparison profile contract");
        }
        return json.readValue(encoded, ComparisonProfile.class);
    }

    public <T> T request(JsonNode node, Class<T> type) {
        T request;
        try {
            write(node);
            request = json.treeToValue(node, type);
        } catch (RuntimeException exception) {
            throw new InvalidComparisonException("정해진 입력 항목과 값의 형식을 확인해 주세요.");
        }
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new InvalidComparisonException("필수 값과 요청 버전을 확인해 주세요.");
        }
        return request;
    }
}

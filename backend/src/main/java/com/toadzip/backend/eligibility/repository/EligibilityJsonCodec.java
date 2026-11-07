package com.toadzip.backend.eligibility.repository;

import com.toadzip.backend.eligibility.domain.EligibilityContent;
import com.toadzip.backend.eligibility.domain.EligibilityProfile;
import com.toadzip.backend.eligibility.domain.EligibilityDecision;
import java.util.List;
import com.toadzip.backend.eligibility.exception.InvalidEligibilityException;

import jakarta.validation.Validator;

import org.springframework.stereotype.Component;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

@Component
public class EligibilityJsonCodec {
    private static final int MAX_CONTENT_BYTES = 4 * 1024 * 1024;
    private final ObjectMapper json;
    private final Validator validator;

    public EligibilityJsonCodec(ObjectMapper json, Validator validator) {
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
            throw new InvalidEligibilityException("비교 데이터는 4 MiB를 넘을 수 없습니다.");
        }
        return encoded;
    }

    public EligibilityContent content(String encoded, int schemaVersion) {
        if (schemaVersion != 2 && schemaVersion != EligibilityContent.SCHEMA_VERSION) {
            throw new IllegalStateException("Unsupported basic comparison content schema");
        }
        return json.readValue(encoded, EligibilityContent.class);
    }

    public EligibilityProfile profile(String encoded, int inputContractVersion) {
        if (inputContractVersion != 2 && inputContractVersion != EligibilityProfile.CONTRACT_VERSION) {
            throw new IllegalStateException("Unsupported basic comparison profile contract");
        }
        return json.readValue(encoded, EligibilityProfile.class);
    }

    public EligibilityDecision decision(String encoded) {
        return json.readValue(encoded, EligibilityDecision.class);
    }

    public List<String> strings(String encoded) {
        return List.of(json.readValue(encoded, String[].class));
    }

    public <T> T request(JsonNode node, Class<T> type) {
        T request;
        try {
            write(node);
            request = json.treeToValue(node, type);
        } catch (RuntimeException exception) {
            throw new InvalidEligibilityException("정해진 입력 항목과 값의 형식을 확인해 주세요.");
        }
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new InvalidEligibilityException("필수 값과 요청 버전을 확인해 주세요.");
        }
        return request;
    }
}

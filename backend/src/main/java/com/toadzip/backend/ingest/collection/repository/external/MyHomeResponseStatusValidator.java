package com.toadzip.backend.ingest.collection.repository.external;

import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class MyHomeResponseStatusValidator implements ExternalDataResponseStatusValidator {

    private static final String SUCCESS = "00";
    private static final String NO_DATA = "03";
    private static final List<String> RETRYABLE_RESULT_CODES = List.of("01", "05", "23");
    private static final String DAILY_RATE_LIMIT_CODE = "22";
    private static final String PER_SECOND_RATE_LIMIT_CODE = "23";

    @Override
    public void validate(JsonNode root) {
        JsonNode header = root.path("response").path("header");
        String code = header.path("resultCode").asString("");
        if (SUCCESS.equals(code) || NO_DATA.equals(code)) {
            return;
        }
        String message = header.path("resultMsg").asString("");
        String reason = "원천 오류 resultCode=" + code + ", " + message;
        if (DAILY_RATE_LIMIT_CODE.equals(code)) {
            throw ExternalDataRequestException.rateLimited(reason);
        }
        if (PER_SECOND_RATE_LIMIT_CODE.equals(code)) {
            throw ExternalDataRequestException.rateLimited(reason, null, true);
        }
        if (RETRYABLE_RESULT_CODES.contains(code)) {
            throw ExternalDataRequestException.retryable(reason);
        }
        throw new ExternalDataRequestException(reason);
    }
}

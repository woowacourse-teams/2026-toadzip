package com.toadzip.backend.ingest.collection.repository.external;

import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class LhResponseStatusValidator implements ExternalDataResponseStatusValidator {

    private static final String SUCCESS = "Y";

    @Override
    public void validate(JsonNode root) {
        List<JsonNode> headers = DataGoKrOpenApiClient.findRows(root, "resHeader");
        if (headers.isEmpty()) {
            throw new ExternalDataRequestException("원천 응답에 resHeader가 없습니다.");
        }
        JsonNode header = headers.getFirst();
        String code = header.path("SS_CODE").asString("");
        if (SUCCESS.equals(code)) {
            return;
        }
        String message = header.path("RS_MSG").asString("");
        throw new ExternalDataRequestException("원천 오류 SS_CODE=" + code + ", " + message);
    }
}

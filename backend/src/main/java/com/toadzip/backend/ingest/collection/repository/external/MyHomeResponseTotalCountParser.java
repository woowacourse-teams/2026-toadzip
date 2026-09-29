package com.toadzip.backend.ingest.collection.repository.external;

import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

final class MyHomeResponseTotalCountParser {

    private MyHomeResponseTotalCountParser() {
    }

    static int parse(JsonNode body, Supplier<ExternalDataRequestException> invalidResponseSchema) {
        JsonNode totalCount = body.path("totalCount");
        if (totalCount.isIntegralNumber() && totalCount.canConvertToInt()) {
            return requireNonNegativeTotalCount(totalCount.intValue(), invalidResponseSchema);
        }
        if (totalCount.isTextual()) {
            return textualTotalCount(totalCount.textValue(), invalidResponseSchema);
        }
        throw invalidResponseSchema.get();
    }

    private static int textualTotalCount(
            String totalCount,
            Supplier<ExternalDataRequestException> invalidResponseSchema
    ) {
        try {
            return requireNonNegativeTotalCount(Integer.parseInt(totalCount), invalidResponseSchema);
        }
        catch (NumberFormatException exception) {
            throw invalidResponseSchema.get();
        }
    }

    private static int requireNonNegativeTotalCount(
            int totalCount,
            Supplier<ExternalDataRequestException> invalidResponseSchema
    ) {
        if (totalCount < 0) {
            throw invalidResponseSchema.get();
        }
        return totalCount;
    }
}

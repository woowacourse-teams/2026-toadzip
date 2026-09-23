package com.toadzip.backend.housing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

public record HousingMapAggregateNodeResponse(
        @Schema(allowableValues = "AGGREGATE") String type,
        String groupKey,
        String groupLabel,
        BigDecimal latitude,
        BigDecimal longitude,
        long uniqueComplexCount,
        int nextStage,
        BigDecimal expansionZoom
) implements HousingMapNodeResponse {

    private static final String TYPE = "AGGREGATE";

    public HousingMapAggregateNodeResponse(
            String groupKey,
            String groupLabel,
            BigDecimal latitude,
            BigDecimal longitude,
            long uniqueComplexCount,
            int nextStage,
            BigDecimal expansionZoom
    ) {
        this(
                TYPE, groupKey, groupLabel, latitude, longitude,
                uniqueComplexCount, nextStage, expansionZoom
        );
    }

    public HousingMapAggregateNodeResponse {
        if (!TYPE.equals(type)) {
            throw new IllegalArgumentException("Aggregate map node type must be " + TYPE);
        }
    }
}

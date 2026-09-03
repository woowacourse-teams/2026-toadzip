package com.toadzip.backend.housing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

public record HousingMapIndividualNodeResponse(
        @Schema(allowableValues = "INDIVIDUAL") String type,
        long complexId,
        String name,
        BigDecimal latitude,
        BigDecimal longitude,
        String rentalType,
        AgencyResponse agency,
        BigDecimal exclusiveAreaMin,
        BigDecimal exclusiveAreaMax,
        Long depositMin,
        Long depositMax,
        Long monthlyRentMin,
        Long monthlyRentMax
) implements HousingMapNodeResponse {

    private static final String TYPE = "INDIVIDUAL";

    public HousingMapIndividualNodeResponse(HousingComplexMapItemResponse item) {
        this(
                TYPE,
                item.complexId(),
                item.name(),
                item.latitude(),
                item.longitude(),
                item.rentalType(),
                item.agency(),
                item.exclusiveAreaMin(),
                item.exclusiveAreaMax(),
                item.depositMin(),
                item.depositMax(),
                item.monthlyRentMin(),
                item.monthlyRentMax()
        );
    }

    public HousingMapIndividualNodeResponse {
        if (!TYPE.equals(type)) {
            throw new IllegalArgumentException("Individual map node type must be " + TYPE);
        }
    }
}

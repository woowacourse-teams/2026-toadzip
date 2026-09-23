package com.toadzip.backend.housing.dto.response;

import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        oneOf = {HousingMapAggregateNodeResponse.class, HousingMapIndividualNodeResponse.class},
        discriminatorProperty = "type",
        discriminatorMapping = {
                @DiscriminatorMapping(
                        value = "AGGREGATE",
                        schema = HousingMapAggregateNodeResponse.class
                ),
                @DiscriminatorMapping(
                        value = "INDIVIDUAL",
                        schema = HousingMapIndividualNodeResponse.class
                )
        }
)
public sealed interface HousingMapNodeResponse permits
        HousingMapAggregateNodeResponse,
        HousingMapIndividualNodeResponse {

    String type();
}

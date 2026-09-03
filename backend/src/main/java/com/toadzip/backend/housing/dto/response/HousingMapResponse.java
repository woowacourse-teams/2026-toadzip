package com.toadzip.backend.housing.dto.response;

import java.util.List;

public record HousingMapResponse(
        int resolvedStage,
        HousingMapRepresentation representation,
        String policyVersion,
        String regionDatasetVersion,
        List<HousingMapNodeResponse> nodes
) {

    public HousingMapResponse {
        nodes = List.copyOf(nodes);
    }
}

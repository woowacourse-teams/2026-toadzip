package com.toadzip.backend.housing.service;

import com.toadzip.backend.housing.dto.response.HousingMapNodeResponse;
import com.toadzip.backend.housing.dto.response.HousingMapRepresentation;
import java.util.List;

record HousingMapNodeResult(
        HousingMapRepresentation representation,
        List<HousingMapNodeResponse> nodes
) {

    HousingMapNodeResult {
        nodes = List.copyOf(nodes);
    }
}

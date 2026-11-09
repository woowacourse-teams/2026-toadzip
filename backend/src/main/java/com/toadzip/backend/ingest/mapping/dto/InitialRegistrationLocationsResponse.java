package com.toadzip.backend.ingest.mapping.dto;

import java.util.List;
import java.util.Map;

public record InitialRegistrationLocationsResponse(Map<String, Long> counts,
        List<InitialRegistrationResponse.Target> targets, long totalElements, int page, int totalPages) {
}

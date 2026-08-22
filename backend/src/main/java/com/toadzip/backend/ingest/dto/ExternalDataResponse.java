package com.toadzip.backend.ingest.dto;

import tools.jackson.databind.JsonNode;

public record ExternalDataResponse(String rawPayload, JsonNode body) {
}

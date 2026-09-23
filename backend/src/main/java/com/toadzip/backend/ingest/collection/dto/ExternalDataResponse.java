package com.toadzip.backend.ingest.collection.dto;

import tools.jackson.databind.JsonNode;

public record ExternalDataResponse(String rawPayload, JsonNode body) {
}

package com.toadzip.backend.ingest.dto;

import tools.jackson.databind.JsonNode;

public record ExternalApiResponse(String apiData, JsonNode responseBody) {
}

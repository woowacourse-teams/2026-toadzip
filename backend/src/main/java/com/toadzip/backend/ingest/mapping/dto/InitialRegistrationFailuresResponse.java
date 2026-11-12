package com.toadzip.backend.ingest.mapping.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InitialRegistrationFailuresResponse(List<Failure> failures, long totalElements, int totalPages) {
    public record Failure(String detail, Instant failedAt, String actor, UUID executionId) { }
}

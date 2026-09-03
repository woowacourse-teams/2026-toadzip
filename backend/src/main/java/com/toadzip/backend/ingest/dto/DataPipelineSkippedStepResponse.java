package com.toadzip.backend.ingest.dto;

public record DataPipelineSkippedStepResponse(
        String stepName,
        String reason,
        Object serverResponse
) {
}

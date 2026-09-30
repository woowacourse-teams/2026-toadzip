package com.toadzip.backend.ingest.pipeline.dto;

public record DataPipelineSkippedStepResponse(
        String stepName,
        String reason,
        Object serverResponse
) {
}

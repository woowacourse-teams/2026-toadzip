package com.toadzip.backend.ingest.pipeline.dto;

public record DataPipelineFailureResponse(
        String stepName,
        String message,
        Object serverResponse
) {
}

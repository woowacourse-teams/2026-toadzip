package com.toadzip.backend.ingest.dto;

public record DataPipelineFailureResponse(
        String stepName,
        String message,
        Object serverResponse
) {
}

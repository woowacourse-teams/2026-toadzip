package com.toadzip.backend.ingest.pipeline.dto;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;

public record DataPipelinePartiallyFailedStepResponse(
        DataPipelineStep step,
        String stepName,
        Object report
) {
}

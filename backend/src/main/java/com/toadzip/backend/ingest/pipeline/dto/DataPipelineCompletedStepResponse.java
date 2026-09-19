package com.toadzip.backend.ingest.pipeline.dto;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;

public record DataPipelineCompletedStepResponse(
        DataPipelineStep step,
        String stepName,
        Object report
) {
}

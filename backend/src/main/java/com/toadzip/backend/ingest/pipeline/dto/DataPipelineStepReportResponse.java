package com.toadzip.backend.ingest.pipeline.dto;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;

public record DataPipelineStepReportResponse(
        DataPipelineStep step,
        String stepName,
        Object report
) {
}

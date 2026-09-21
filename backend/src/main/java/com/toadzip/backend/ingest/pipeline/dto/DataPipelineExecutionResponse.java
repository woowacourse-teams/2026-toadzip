package com.toadzip.backend.ingest.pipeline.dto;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionTrigger;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DataPipelineExecutionResponse(
        UUID executionId,
        DataPipelineType type,
        DataPipelineExecutionTrigger trigger,
        Instant scheduledAt,
        UUID upstreamExecutionId,
        DataPipelineExecutionStatus status,
        DataPipelineStep currentStep,
        String currentStepName,
        int currentStepIndex,
        int totalStepCount,
        List<String> completedSteps,
        List<DataPipelineCompletedStepResponse> completedStepResults,
        List<DataPipelineSkippedStepResponse> skippedSteps,
        List<DataPipelinePartiallyFailedStepResponse> partiallyFailedSteps,
        DataPipelineFailureResponse failure,
        Instant startedAt,
        Instant finishedAt
) {

    public DataPipelineExecutionResponse {
        completedSteps = List.copyOf(completedSteps);
        completedStepResults = List.copyOf(completedStepResults);
        skippedSteps = List.copyOf(skippedSteps);
        partiallyFailedSteps = List.copyOf(partiallyFailedSteps);
    }

    public static DataPipelineExecutionResponse idle(DataPipelineType type) {
        return new DataPipelineExecutionResponse(
                null,
                type,
                null,
                null,
                null,
                DataPipelineExecutionStatus.IDLE,
                null,
                null,
                0,
                type.steps().size(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null
        );
    }
}

package com.toadzip.backend.ingest.dto;

import com.toadzip.backend.ingest.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.domain.DataPipelineStep;
import com.toadzip.backend.ingest.domain.DataPipelineType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DataPipelineExecutionResponse(
        UUID executionId,
        DataPipelineType type,
        DataPipelineExecutionStatus status,
        DataPipelineStep currentStep,
        String currentStepName,
        int currentStepIndex,
        int totalStepCount,
        List<String> completedSteps,
        DataPipelineFailureResponse failure,
        Instant startedAt,
        Instant finishedAt
) {

    public DataPipelineExecutionResponse {
        completedSteps = List.copyOf(completedSteps);
    }

    public static DataPipelineExecutionResponse idle(DataPipelineType type) {
        return new DataPipelineExecutionResponse(
                null,
                type,
                DataPipelineExecutionStatus.IDLE,
                null,
                null,
                0,
                type.steps().size(),
                List.of(),
                null,
                null,
                null
        );
    }
}

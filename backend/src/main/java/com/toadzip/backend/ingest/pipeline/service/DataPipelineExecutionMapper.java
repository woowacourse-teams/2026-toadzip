package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineCompletedStepResponse;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineExecutionResponse;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineFailureResponse;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelinePartiallyFailedStepResponse;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineSkippedStepResponse;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class DataPipelineExecutionMapper {

    private final ObjectMapper objectMapper;

    public DataPipelineExecutionMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DataPipelineExecutionResponse response(DataPipelineExecution execution) {
        DataPipelineStep currentStep = execution.getCurrentStep();
        List<String> completedSteps = execution.getCompletedSteps()
                .stream()
                .map(DataPipelineStep::displayName)
                .toList();
        return new DataPipelineExecutionResponse(
                execution.getExecutionId(),
                execution.getType(),
                execution.getExecutionTrigger(),
                execution.getScheduledAt(),
                execution.getUpstreamExecutionId(),
                execution.getStatus(),
                currentStep,
                stepName(currentStep),
                currentStepIndex(currentStep),
                execution.getType().steps().size(),
                completedSteps,
                completedStepResponses(execution),
                skippedStepResponses(execution),
                partiallyFailedStepResponses(execution),
                failureResponse(execution),
                execution.getStartedAt(),
                execution.getFinishedAt()
        );
    }

    private List<DataPipelinePartiallyFailedStepResponse> partiallyFailedStepResponses(
            DataPipelineExecution execution
    ) {
        return execution.getPartiallyFailedSteps()
                .stream()
                .map(partiallyFailedStep -> new DataPipelinePartiallyFailedStepResponse(
                        partiallyFailedStep.getStep(),
                        partiallyFailedStep.getStep().displayName(),
                        deserializeReport(partiallyFailedStep.getReport())
                ))
                .toList();
    }

    private List<DataPipelineCompletedStepResponse> completedStepResponses(
            DataPipelineExecution execution
    ) {
        return execution.getCompletedStepResults()
                .stream()
                .map(completedStep -> new DataPipelineCompletedStepResponse(
                        completedStep.getStep(),
                        completedStep.getStep().displayName(),
                        deserializeReport(completedStep.getReport())
                ))
                .toList();
    }

    private List<DataPipelineSkippedStepResponse> skippedStepResponses(
            DataPipelineExecution execution
    ) {
        return execution.getSkippedSteps()
                .stream()
                .map(skippedStep -> new DataPipelineSkippedStepResponse(
                        skippedStep.getStep().displayName(),
                        skippedStep.getReason(),
                        deserializeReport(skippedStep.getServerResponse())
                ))
                .toList();
    }

    private DataPipelineFailureResponse failureResponse(DataPipelineExecution execution) {
        if (execution.getFailureMessage() == null) {
            return null;
        }
        return new DataPipelineFailureResponse(
                stepName(execution.getFailedStep()),
                execution.getFailureMessage(),
                deserializeReport(execution.getFailureServerResponse())
        );
    }

    private int currentStepIndex(DataPipelineStep currentStep) {
        if (currentStep == null) {
            return 0;
        }
        return currentStep.sequence();
    }

    private String stepName(DataPipelineStep step) {
        if (step == null) {
            return null;
        }
        return step.displayName();
    }

    private Object deserializeReport(String report) {
        if (report == null) {
            return null;
        }
        try {
            return objectMapper.readValue(report, Object.class);
        }
        catch (JacksonException exception) {
            throw new IllegalStateException(
                    "저장된 파이프라인 실행 보고서를 읽을 수 없습니다.",
                    exception
            );
        }
    }
}

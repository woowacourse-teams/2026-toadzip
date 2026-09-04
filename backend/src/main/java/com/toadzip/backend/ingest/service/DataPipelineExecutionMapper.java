package com.toadzip.backend.ingest.service;

import com.toadzip.backend.ingest.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.domain.DataPipelineStep;
import com.toadzip.backend.ingest.dto.DataPipelineExecutionResponse;
import com.toadzip.backend.ingest.dto.DataPipelineFailureResponse;
import com.toadzip.backend.ingest.dto.DataPipelineSkippedStepResponse;
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
                execution.getStatus(),
                currentStep,
                stepName(currentStep),
                currentStepIndex(currentStep),
                execution.getType().steps().size(),
                completedSteps,
                skippedStepResponses(execution),
                failureResponse(execution),
                execution.getStartedAt(),
                execution.getFinishedAt()
        );
    }

    private List<DataPipelineSkippedStepResponse> skippedStepResponses(
            DataPipelineExecution execution
    ) {
        return execution.getSkippedSteps()
                .stream()
                .map(skippedStep -> new DataPipelineSkippedStepResponse(
                        skippedStep.getStep().displayName(),
                        skippedStep.getReason(),
                        deserializeServerResponse(skippedStep.getServerResponse())
                ))
                .toList();
    }

    public String serializeServerResponse(Object serverResponse) {
        if (serverResponse == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(serverResponse);
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("파이프라인 실패 응답을 저장할 수 없습니다.", exception);
        }
    }

    private DataPipelineFailureResponse failureResponse(DataPipelineExecution execution) {
        if (execution.getFailureMessage() == null) {
            return null;
        }
        return new DataPipelineFailureResponse(
                stepName(execution.getFailedStep()),
                execution.getFailureMessage(),
                deserializeServerResponse(execution.getFailureServerResponse())
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

    private Object deserializeServerResponse(String serverResponse) {
        if (serverResponse == null) {
            return null;
        }
        try {
            return objectMapper.readValue(serverResponse, Object.class);
        }
        catch (JacksonException exception) {
            throw new IllegalStateException(
                    "저장된 파이프라인 실패 응답을 읽을 수 없습니다.",
                    exception
            );
        }
    }
}

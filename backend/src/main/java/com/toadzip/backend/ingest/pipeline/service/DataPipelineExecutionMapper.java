package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineWorkProgress;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineExecutionResponse;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineFailureResponse;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineSkippedStepResponse;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineStepReportResponse;
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
                currentStepIndex(execution),
                totalSteps(execution),
                completedSteps,
                completedStepResponses(execution),
                skippedStepResponses(execution),
                partiallyFailedStepResponses(execution),
                failureResponse(execution),
                execution.getStartedAt(),
                execution.getFinishedAt(),
                execution.isStopRequested(),
                execution.getExternalRequestCount(),
                execution.getLastRequestDescription(),
                execution.getLastProgressAt(),
                workProgress(execution),
                execution.getTargetAnnouncementIdentifier()
        );
    }

    private List<DataPipelineStepReportResponse> partiallyFailedStepResponses(
            DataPipelineExecution execution
    ) {
        return execution.getPartiallyFailedSteps()
                .stream()
                .map(partiallyFailedStep -> new DataPipelineStepReportResponse(
                        partiallyFailedStep.getStep(),
                        partiallyFailedStep.getStep().displayName(),
                        deserializeReport(partiallyFailedStep.getReport())
                ))
                .toList();
    }

    private List<DataPipelineStepReportResponse> completedStepResponses(
            DataPipelineExecution execution
    ) {
        return execution.getCompletedStepResults()
                .stream()
                .map(completedStep -> new DataPipelineStepReportResponse(
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

    /** 지난 SH·GH 실행은 지금보다 많은 단계(수집·정제 분리)를 기록했을 수 있어, 기록된 단계 수보다 작게 보이지 않게 한다. */
    private int totalSteps(DataPipelineExecution execution) {
        int recorded = execution.getCompletedStepResults().size() + execution.getSkippedSteps().size()
                + (execution.getFailedStep() == null ? 0 : 1);
        return Math.max(execution.getType().steps().size(), recorded);
    }

    private int currentStepIndex(DataPipelineExecution execution) {
        DataPipelineStep currentStep = execution.getCurrentStep();
        // 지난 실행에는 지금 유형 정의에서 빠진 옛 단계(마이홈 공고 정제·LH 보강)가 남아 있을 수 있다.
        if (currentStep == null || !execution.getType().steps().contains(currentStep)) {
            return 0;
        }
        return execution.getType().sequenceOf(currentStep);
    }

    private String stepName(DataPipelineStep step) {
        if (step == null) {
            return null;
        }
        return step.displayName();
    }

    private DataPipelineWorkProgress workProgress(DataPipelineExecution execution) {
        if (execution.getWorkProgress() == null) {
            return null;
        }
        return objectMapper.readValue(execution.getWorkProgress(), DataPipelineWorkProgress.class);
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

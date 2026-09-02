package com.toadzip.backend.ingest.service;

import com.toadzip.backend.ingest.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.domain.DataPipelineStep;
import com.toadzip.backend.ingest.domain.DataPipelineType;
import com.toadzip.backend.ingest.dto.DataPipelineExecutionResponse;
import com.toadzip.backend.ingest.dto.DataPipelineFailureResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class DataPipelineExecutionState {

    private final UUID executionId;
    private final DataPipelineType type;
    private final Instant startedAt;
    private final List<String> completedSteps = new ArrayList<>();

    private DataPipelineExecutionStatus status = DataPipelineExecutionStatus.RUNNING;
    private DataPipelineStep currentStep;
    private DataPipelineFailureResponse failure;
    private Instant finishedAt;

    DataPipelineExecutionState(UUID executionId, DataPipelineType type, Instant startedAt) {
        this.executionId = executionId;
        this.type = type;
        this.startedAt = startedAt;
    }

    synchronized void startStep(DataPipelineStep step) {
        requireRunning();
        if (!step.belongsTo(type)) {
            throw new IllegalStateException("실행 유형에 속하지 않는 단계입니다.");
        }
        currentStep = step;
    }

    synchronized void completeStep(DataPipelineStep step) {
        requireRunning();
        if (currentStep != step) {
            throw new IllegalStateException("현재 실행 중인 단계만 완료할 수 있습니다.");
        }
        completedSteps.add(step.displayName());
        currentStep = null;
    }

    synchronized void complete(Instant completedAt) {
        requireRunning();
        if (completedSteps.size() != type.steps().size()) {
            throw new IllegalStateException("모든 단계를 완료한 뒤 파이프라인을 완료할 수 있습니다.");
        }
        status = DataPipelineExecutionStatus.COMPLETED;
        currentStep = null;
        finishedAt = completedAt;
    }

    synchronized void fail(
            DataPipelineStep failedStep,
            String message,
            Object serverResponse,
            Instant failedAt
    ) {
        requireRunning();
        status = DataPipelineExecutionStatus.FAILED;
        currentStep = failedStep;
        failure = new DataPipelineFailureResponse(
                failedStep == null ? null : failedStep.displayName(),
                message,
                serverResponse
        );
        finishedAt = failedAt;
    }

    synchronized DataPipelineStep currentStep() {
        return currentStep;
    }

    synchronized DataPipelineExecutionResponse response() {
        int currentStepIndex = currentStep == null ? 0 : currentStep.sequence();
        String currentStepName = currentStep == null ? null : currentStep.displayName();
        return new DataPipelineExecutionResponse(
                executionId,
                type,
                status,
                currentStep,
                currentStepName,
                currentStepIndex,
                type.steps().size(),
                completedSteps,
                failure,
                startedAt,
                finishedAt
        );
    }

    private void requireRunning() {
        if (status != DataPipelineExecutionStatus.RUNNING) {
            throw new IllegalStateException("실행 중인 데이터 파이프라인만 상태를 변경할 수 있습니다.");
        }
    }
}

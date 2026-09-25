package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionTrigger;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataPipelineExecutionStateService {

    private final DataPipelineExecutionRepository executionRepository;

    public DataPipelineExecutionStateService(
            DataPipelineExecutionRepository executionRepository
    ) {
        this.executionRepository = executionRepository;
    }

    @Transactional
    public DataPipelineExecution create(
            UUID executionId,
            DataPipelineType type,
            Instant startedAt
    ) {
        return create(
                executionId,
                type,
                startedAt,
                DataPipelineExecutionTrigger.MANUAL,
                null,
                null
        );
    }

    @Transactional
    public DataPipelineExecution create(
            UUID executionId,
            DataPipelineType type,
            Instant startedAt,
            DataPipelineExecutionTrigger executionTrigger,
            Instant scheduledAt,
            UUID upstreamExecutionId
    ) {
        DataPipelineExecution execution = DataPipelineExecution.start(
                executionId,
                type,
                startedAt,
                executionTrigger,
                scheduledAt,
                upstreamExecutionId
        );
        return executionRepository.saveAndFlush(execution);
    }

    @Transactional
    public void startStep(UUID executionId, DataPipelineStep step) {
        DataPipelineExecution execution = find(executionId);
        execution.startStep(step);
        executionRepository.flush();
    }

    @Transactional
    public void completeStep(UUID executionId, DataPipelineStep step, String report) {
        DataPipelineExecution execution = find(executionId);
        execution.completeStep(step, report);
        executionRepository.flush();
    }

    @Transactional
    public void completeStepWithWarnings(UUID executionId, DataPipelineStep step, String report) {
        DataPipelineExecution execution = find(executionId);
        execution.completeStepWithWarnings(step, report);
        executionRepository.flush();
    }

    @Transactional
    public void startStepAfterPartialFailure(
            UUID executionId,
            DataPipelineStep partiallyFailedStep,
            DataPipelineStep nextStep
    ) {
        DataPipelineExecution execution = find(executionId);
        execution.startStepAfterPartialFailure(partiallyFailedStep, nextStep);
        executionRepository.flush();
    }

    @Transactional
    public void recordPartialFailure(UUID executionId, DataPipelineStep step, String report) {
        DataPipelineExecution execution = find(executionId);
        execution.recordPartialFailure(step, report);
        executionRepository.flush();
    }

    @Transactional
    public void skipStep(
            UUID executionId,
            DataPipelineStep step,
            String reason,
            String serverResponse
    ) {
        DataPipelineExecution execution = find(executionId);
        execution.skipStep(step, reason, serverResponse);
        executionRepository.flush();
    }

    @Transactional
    public void complete(UUID executionId, Instant completedAt) {
        DataPipelineExecution execution = find(executionId);
        execution.complete(completedAt);
        executionRepository.flush();
    }

    @Transactional
    public void fail(
            UUID executionId,
            DataPipelineStep failedStep,
            String message,
            String serverResponse,
            Instant failedAt
    ) {
        DataPipelineExecution execution = find(executionId);
        if (!execution.isRunning()) {
            return;
        }
        execution.fail(failedStep, message, serverResponse, failedAt);
        executionRepository.flush();
    }

    @Transactional
    public int recoverInterruptedBefore(Instant cutoff, Instant failedAt, String message) {
        var interrupted = executionRepository.findInterruptedBeforeForUpdate(cutoff);
        interrupted.forEach(execution ->
                execution.fail(execution.getCurrentStep(), message, null, failedAt));
        executionRepository.flush();
        return interrupted.size();
    }

    @Transactional
    public boolean recoverInterrupted(
            UUID executionId, Instant cutoff, Instant failedAt, String message
    ) {
        return executionRepository.findInterruptedForUpdate(executionId, cutoff)
                .map(execution -> {
                    execution.fail(execution.getCurrentStep(), message, null, failedAt);
                    executionRepository.flush();
                    return true;
                })
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public DataPipelineStep findCurrentStep(UUID executionId) {
        return find(executionId).getCurrentStep();
    }

    private DataPipelineExecution find(UUID executionId) {
        return executionRepository.findByExecutionId(executionId)
                .orElseThrow(() -> new IllegalStateException(
                        "데이터 파이프라인 실행을 찾을 수 없습니다: " + executionId
                ));
    }
}

package com.toadzip.backend.ingest.service;

import com.toadzip.backend.ingest.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.domain.DataPipelineStep;
import com.toadzip.backend.ingest.domain.DataPipelineType;
import com.toadzip.backend.ingest.repository.DataPipelineExecutionRepository;
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
        DataPipelineExecution execution = DataPipelineExecution.start(
                executionId,
                type,
                startedAt
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
    public void completeStep(UUID executionId, DataPipelineStep step) {
        DataPipelineExecution execution = find(executionId);
        execution.completeStep(step);
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

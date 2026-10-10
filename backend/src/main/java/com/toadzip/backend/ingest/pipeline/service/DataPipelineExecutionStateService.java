package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.collection.history.domain.CollectionStatus;
import com.toadzip.backend.ingest.collection.history.repository.SourceCollectionRecordRepository;
import com.toadzip.backend.ingest.exception.exception.DataPipelineExecutionNotFoundException;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineWorkProgress;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class DataPipelineExecutionStateService {

    private final DataPipelineExecutionRepository executionRepository;
    private final SourceCollectionRecordRepository collectionRecords;
    private final ObjectMapper objectMapper;

    public DataPipelineExecutionStateService(
            DataPipelineExecutionRepository executionRepository, ObjectMapper objectMapper,
            SourceCollectionRecordRepository collectionRecords
    ) {
        this.executionRepository = executionRepository;
        this.objectMapper = objectMapper;
        this.collectionRecords = collectionRecords;
    }

    @Transactional
    public void recordWorkProgress(UUID executionId, DataPipelineWorkProgress progress) {
        executionRepository.recordWorkProgress(executionId, objectMapper.writeValueAsString(progress));
    }

    @Transactional
    public DataPipelineExecution create(
            UUID executionId,
            DataPipelineType type,
            Instant startedAt
    ) {
        DataPipelineExecution execution = DataPipelineExecution.start(executionId, type, startedAt);
        return executionRepository.saveAndFlush(execution);
    }

    @Transactional
    public int heartbeat(Long id, Instant heartbeatAt) {
        return executionRepository.updateHeartbeat(id, heartbeatAt);
    }

    @Transactional
    public DataPipelineExecution createRegistration(UUID executionId, String identifier, Instant startedAt) {
        return executionRepository.saveAndFlush(
                DataPipelineExecution.startRegistration(executionId, identifier, startedAt));
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
        if (execution.isStopRequested()) {
            execution.stop(completedAt);
            return;
        }
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
        DataPipelineExecution execution = executionRepository.findByExecutionIdForUpdate(executionId)
                .orElseThrow(() -> new IllegalStateException("실행을 찾을 수 없습니다: " + executionId));
        if (!execution.isRunning()) {
            return;
        }
        execution.fail(failedStep, message, serverResponse, failedAt);
        executionRepository.flush();
    }

    @Transactional
    public int recoverInterruptedBefore(Instant cutoff, Instant failedAt, String message) {
        var interrupted = executionRepository.findInterruptedBeforeForUpdate(cutoff);
        interrupted.forEach(execution -> failInterruptedExecution(execution, failedAt, message));
        executionRepository.flush();
        return interrupted.size();
    }

    @Transactional
    public boolean recoverInterrupted(
            UUID executionId, Instant cutoff, Instant failedAt, String message
    ) {
        return executionRepository.findInterruptedForUpdate(executionId, cutoff)
                .map(execution -> {
                    failInterruptedExecution(execution, failedAt, message);
                    executionRepository.flush();
                    return true;
                })
                .orElse(false);
    }

    private void failInterruptedExecution(DataPipelineExecution execution, Instant failedAt, String message) {
        execution.fail(execution.getCurrentStep(), message, null, failedAt);
        collectionRecords.findAllByExecutionIdAndStatusOrderByStartedAtAscIdAsc(
                execution.getExecutionId(), CollectionStatus.RUNNING).forEach(
                        record -> record.fail(failedAt, "InterruptedExecution", message));
        collectionRecords.flush();
    }

    @Transactional
    public void recoverTerminalCollections(Instant recoveredAt) {
        executionRepository.findTerminalWithRunningCollectionsForUpdate().forEach(execution -> {
            if (!execution.isRunning()) {
                collectionRecords.findAllByExecutionIdAndStatusOrderByStartedAtAscIdAsc(
                        execution.getExecutionId(), CollectionStatus.RUNNING).forEach(record -> record.fail(
                                recoveredAt, "UnfinishedCollection",
                                "실행이 종료되었으나 수집 결과를 저장하지 못했습니다. 원천과 실패 원인을 확인한 뒤 다시 수집해 주세요."));
            }
        });
        collectionRecords.flush();
    }

    @Transactional(readOnly = true)
    public DataPipelineStep findCurrentStep(UUID executionId) {
        return executionRepository.findByExecutionId(executionId)
                .orElseThrow(() -> new IllegalStateException("실행을 찾을 수 없습니다: " + executionId))
                .getCurrentStep();
    }

    @Transactional
    public DataPipelineExecution requestStop(UUID executionId) {
        DataPipelineExecution execution = executionRepository.findByExecutionIdForUpdate(executionId)
                .orElseThrow(() -> new DataPipelineExecutionNotFoundException(
                        "데이터 파이프라인 실행을 찾을 수 없습니다: " + executionId
                ));
        execution.requestStop();
        executionRepository.flush();
        return execution;
    }

    @Transactional(readOnly = true)
    public boolean isStopRequested(UUID executionId) {
        return executionRepository.existsByExecutionIdAndStopRequestedTrue(executionId);
    }

    @Transactional
    public void stop(UUID executionId, Instant stoppedAt) {
        DataPipelineExecution execution = find(executionId);
        if (execution.isRunning()) {
            execution.stop(stoppedAt);
        }
    }

    @Transactional
    public void recordRequestStarted(UUID executionId, String description, Instant now) {
        executionRepository.recordRequestStarted(executionId, description, now);
    }

    @Transactional
    public void recordRequestFinished(UUID executionId, Instant now) {
        executionRepository.recordRequestFinished(executionId, now);
    }

    private DataPipelineExecution find(UUID executionId) {
        return executionRepository.findByExecutionIdForUpdate(executionId)
                .orElseThrow(() -> new IllegalStateException(
                        "데이터 파이프라인 실행을 찾을 수 없습니다: " + executionId
                ));
    }
}

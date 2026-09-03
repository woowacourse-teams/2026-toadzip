package com.toadzip.backend.ingest.service;

import com.toadzip.backend.ingest.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.domain.DataPipelineStep;
import com.toadzip.backend.ingest.domain.DataPipelineType;
import com.toadzip.backend.ingest.dto.DataPipelineExecutionResponse;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.repository.DataPipelineExecutionLock;
import com.toadzip.backend.ingest.repository.DataPipelineExecutionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DataPipelineExecutionService {

    private static final String ALREADY_RUNNING_MESSAGE =
            "데이터 수집·정제 작업이 이미 실행 중입니다.";
    private static final String INTERNAL_FAILURE_MESSAGE =
            "현재 단계를 처리하는 중 서버 오류가 발생했습니다.";
    private static final String INTERRUPTED_FAILURE_MESSAGE =
            "서버 실행이 중단되어 데이터 수집·정제 작업을 종료했습니다.";
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    private static final Duration EXECUTION_LEASE_TIMEOUT = Duration.ofMinutes(2);

    private final DataPipelineRunner runner;
    private final DataPipelineExecutionLock executionLock;
    private final DataPipelineExecutionRepository executionRepository;
    private final DataPipelineExecutionStateService executionStateService;
    private final Executor executor;
    private final ScheduledExecutorService heartbeatExecutor;
    private final Clock clock;
    private final DataPipelineExecutionMapper executionMapper;

    public DataPipelineExecutionService(
            DataPipelineRunner runner,
            DataPipelineExecutionLock executionLock,
            DataPipelineExecutionRepository executionRepository,
            DataPipelineExecutionStateService executionStateService,
            @Qualifier("dataPipelineExecutor") Executor executor,
            @Qualifier("dataPipelineHeartbeatExecutor") ScheduledExecutorService heartbeatExecutor,
            Clock clock,
            DataPipelineExecutionMapper executionMapper
    ) {
        this.runner = runner;
        this.executionLock = executionLock;
        this.executionRepository = executionRepository;
        this.executionStateService = executionStateService;
        this.executor = executor;
        this.heartbeatExecutor = heartbeatExecutor;
        this.clock = clock;
        this.executionMapper = executionMapper;
    }

    public DataPipelineExecutionResponse start(DataPipelineType type) {
        DataPipelineExecutionLock.Lease lease = executionLock.tryAcquire()
                .orElseThrow(() -> new IngestAlreadyRunningException(ALREADY_RUNNING_MESSAGE));
        UUID executionId = UUID.randomUUID();
        DataPipelineExecution execution;
        try {
            execution = executionStateService.create(executionId, type, Instant.now(clock));
        }
        catch (RuntimeException exception) {
            lease.close();
            throw exception;
        }
        DataPipelineExecutionResponse acceptedResponse = executionMapper.response(execution);
        ScheduledFuture<?> heartbeatTask;
        try {
            heartbeatTask = scheduleHeartbeat(execution);
        }
        catch (RuntimeException exception) {
            lease.close();
            recordFailure(executionId, type, null, INTERNAL_FAILURE_MESSAGE, null);
            throw exception;
        }
        try {
            executor.execute(() -> execute(executionId, type, lease, heartbeatTask));
        }
        catch (RuntimeException exception) {
            heartbeatTask.cancel(false);
            lease.close();
            recordFailure(executionId, type, null, INTERNAL_FAILURE_MESSAGE, null);
            throw exception;
        }
        return acceptedResponse;
    }

    public DataPipelineExecutionResponse findLatest(DataPipelineType type) {
        return executionRepository.findFirstByTypeOrderByIdDesc(type)
                .map(this::recoverInterruptedExecution)
                .map(executionMapper::response)
                .orElseGet(() -> DataPipelineExecutionResponse.idle(type));
    }

    private void execute(
            UUID executionId,
            DataPipelineType type,
            DataPipelineExecutionLock.Lease lease,
            ScheduledFuture<?> heartbeatTask
    ) {
        try (lease) {
            runner.run(type, progressListener(executionId));
            executionStateService.complete(executionId, Instant.now(clock));
        }
        catch (DataPipelinePartialFailureException exception) {
            recordFailure(
                    executionId,
                    type,
                    exception.getStep(),
                    exception.getMessage(),
                    exception.getServerResponse()
            );
        }
        catch (RuntimeException exception) {
            DataPipelineStep failedStep = findCurrentStep(executionId);
            recordFailure(executionId, type, failedStep, INTERNAL_FAILURE_MESSAGE, null);
            log.error(
                    "데이터 수집·정제 단계 실행에 실패했습니다: type={}, step={}",
                    type,
                    failedStep,
                    exception
            );
        }
        finally {
            heartbeatTask.cancel(false);
        }
    }

    private DataPipelineProgressListener progressListener(UUID executionId) {
        return new DataPipelineProgressListener() {
            @Override
            public void started(DataPipelineStep step) {
                executionStateService.startStep(executionId, step);
            }

            @Override
            public void completed(DataPipelineStep step) {
                executionStateService.completeStep(executionId, step);
            }

            @Override
            public void skipped(DataPipelineStep step, String reason, Object serverResponse) {
                executionStateService.skipStep(
                        executionId,
                        step,
                        reason,
                        executionMapper.serializeServerResponse(serverResponse)
                );
            }
        };
    }

    private void recordFailure(
            UUID executionId,
            DataPipelineType type,
            DataPipelineStep failedStep,
            String message,
            Object serverResponse
    ) {
        try {
            executionStateService.fail(
                    executionId,
                    failedStep,
                    message,
                    executionMapper.serializeServerResponse(serverResponse),
                    Instant.now(clock)
            );
        }
        catch (RuntimeException exception) {
            log.error(
                    "데이터 수집·정제 실패 상태를 저장하지 못했습니다: type={}, step={}",
                    type,
                    failedStep,
                    exception
            );
        }
    }

    private DataPipelineStep findCurrentStep(UUID executionId) {
        try {
            return executionStateService.findCurrentStep(executionId);
        }
        catch (RuntimeException exception) {
            log.error(
                    "데이터 수집·정제 현재 단계를 조회하지 못했습니다: executionId={}",
                    executionId,
                    exception
            );
            return null;
        }
    }

    private ScheduledFuture<?> scheduleHeartbeat(DataPipelineExecution execution) {
        long intervalSeconds = HEARTBEAT_INTERVAL.toSeconds();
        return heartbeatExecutor.scheduleWithFixedDelay(
                () -> updateHeartbeat(execution),
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS
        );
    }

    private void updateHeartbeat(DataPipelineExecution execution) {
        try {
            executionRepository.updateHeartbeat(execution.getId(), Instant.now(clock));
        }
        catch (RuntimeException exception) {
            log.error(
                    "데이터 수집·정제 실행 heartbeat 갱신에 실패했습니다: executionId={}",
                    execution.getExecutionId(),
                    exception
            );
        }
    }

    private DataPipelineExecution recoverInterruptedExecution(DataPipelineExecution execution) {
        if (!isLeaseExpired(execution) || executionLock.isHeld()) {
            return execution;
        }
        recordFailure(
                execution.getExecutionId(),
                execution.getType(),
                null,
                INTERRUPTED_FAILURE_MESSAGE,
                null
        );
        return executionRepository.findByExecutionId(execution.getExecutionId())
                .orElse(execution);
    }

    private boolean isLeaseExpired(DataPipelineExecution execution) {
        if (!execution.isRunning()) {
            return false;
        }
        Instant leaseDeadline = execution.getHeartbeatAt().plus(EXECUTION_LEASE_TIMEOUT);
        return leaseDeadline.isBefore(Instant.now(clock));
    }
}

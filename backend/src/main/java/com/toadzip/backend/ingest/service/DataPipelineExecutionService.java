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
    private static final String COMPLETION_PERSISTENCE_FAILURE_MESSAGE =
            "완료 결과를 저장하는 중 서버 오류가 발생했습니다.";
    private static final String INTERRUPTED_FAILURE_MESSAGE =
            "서버 실행이 중단되어 데이터 수집·정제 작업을 종료했습니다.";
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    private static final Duration EXECUTION_LEASE_TIMEOUT = Duration.ofMinutes(2);

    private final DataPipelineRunner runner;
    private final DataPipelineExecutionLock executionLock;
    private final DataPipelineExecutionRepository executionRepository;
    private final Executor executor;
    private final ScheduledExecutorService heartbeatExecutor;
    private final Clock clock;
    private final DataPipelineExecutionMapper executionMapper;

    public DataPipelineExecutionService(
            DataPipelineRunner runner,
            DataPipelineExecutionLock executionLock,
            DataPipelineExecutionRepository executionRepository,
            @Qualifier("dataPipelineExecutor") Executor executor,
            @Qualifier("dataPipelineHeartbeatExecutor") ScheduledExecutorService heartbeatExecutor,
            Clock clock,
            DataPipelineExecutionMapper executionMapper
    ) {
        this.runner = runner;
        this.executionLock = executionLock;
        this.executionRepository = executionRepository;
        this.executor = executor;
        this.heartbeatExecutor = heartbeatExecutor;
        this.clock = clock;
        this.executionMapper = executionMapper;
    }

    public DataPipelineExecutionResponse start(DataPipelineType type) {
        DataPipelineExecutionLock.Lease lease = executionLock.tryAcquire()
                .orElseThrow(() -> new IngestAlreadyRunningException(ALREADY_RUNNING_MESSAGE));
        DataPipelineExecution execution = DataPipelineExecution.start(
                UUID.randomUUID(),
                type,
                Instant.now(clock)
        );
        try {
            persist(execution);
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
            recordFailure(execution, null, INTERNAL_FAILURE_MESSAGE, null);
            throw exception;
        }
        try {
            executor.execute(() -> execute(execution, type, lease, heartbeatTask));
        }
        catch (RuntimeException exception) {
            heartbeatTask.cancel(false);
            lease.close();
            recordFailure(execution, null, INTERNAL_FAILURE_MESSAGE, null);
            throw exception;
        }
        return acceptedResponse;
    }

    public DataPipelineExecutionResponse findLatest(DataPipelineType type) {
        return executionRepository.findFirstByTypeOrderByStartedAtDescIdDesc(type)
                .map(this::recoverInterruptedExecution)
                .map(executionMapper::response)
                .orElseGet(() -> DataPipelineExecutionResponse.idle(type));
    }

    private void execute(
            DataPipelineExecution execution,
            DataPipelineType type,
            DataPipelineExecutionLock.Lease lease,
            ScheduledFuture<?> heartbeatTask
    ) {
        try (lease) {
            runner.run(type, progressListener(execution));
            execution.complete(Instant.now(clock));
            persist(execution);
        }
        catch (DataPipelinePartialFailureException exception) {
            recordFailure(
                    execution,
                    exception.getStep(),
                    exception.getMessage(),
                    exception.getServerResponse()
            );
        }
        catch (RuntimeException exception) {
            DataPipelineStep failedStep = execution.getCurrentStep();
            recordFailure(execution, failedStep, INTERNAL_FAILURE_MESSAGE, null);
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

    private DataPipelineProgressListener progressListener(DataPipelineExecution execution) {
        return new DataPipelineProgressListener() {
            @Override
            public void started(DataPipelineStep step) {
                execution.startStep(step);
                persist(execution);
            }

            @Override
            public void completed(DataPipelineStep step) {
                execution.completeStep(step);
                persist(execution);
            }
        };
    }

    private void recordFailure(
            DataPipelineExecution execution,
            DataPipelineStep failedStep,
            String message,
            Object serverResponse
    ) {
        if (execution.isCompleted()) {
            recordCompletionPersistenceFailure(execution);
            return;
        }
        if (!execution.isRunning()) {
            return;
        }
        try {
            execution.fail(
                    failedStep,
                    message,
                    executionMapper.serializeServerResponse(serverResponse),
                    Instant.now(clock)
            );
            persist(execution);
        }
        catch (RuntimeException exception) {
            log.error(
                    "데이터 수집·정제 실패 상태를 저장하지 못했습니다: type={}, step={}",
                    execution.getType(),
                    failedStep,
                    exception
            );
        }
    }

    private void recordCompletionPersistenceFailure(DataPipelineExecution execution) {
        try {
            execution.failCompletionPersistence(
                    COMPLETION_PERSISTENCE_FAILURE_MESSAGE,
                    Instant.now(clock)
            );
            persist(execution);
        }
        catch (RuntimeException exception) {
            log.error(
                    "데이터 수집·정제 완료 저장 실패 상태를 저장하지 못했습니다: type={}",
                    execution.getType(),
                    exception
            );
        }
    }

    private void persist(DataPipelineExecution execution) {
        executionRepository.saveAndFlush(execution);
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
        execution.fail(null, INTERRUPTED_FAILURE_MESSAGE, null, Instant.now(clock));
        persist(execution);
        return execution;
    }

    private boolean isLeaseExpired(DataPipelineExecution execution) {
        if (!execution.isRunning()) {
            return false;
        }
        Instant leaseDeadline = execution.getHeartbeatAt().plus(EXECUTION_LEASE_TIMEOUT);
        return leaseDeadline.isBefore(Instant.now(clock));
    }
}

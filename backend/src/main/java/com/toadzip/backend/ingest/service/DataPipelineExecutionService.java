package com.toadzip.backend.ingest.service;

import com.toadzip.backend.ingest.domain.DataPipelineStep;
import com.toadzip.backend.ingest.domain.DataPipelineType;
import com.toadzip.backend.ingest.dto.DataPipelineExecutionResponse;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.repository.DataPipelineExecutionLock;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DataPipelineExecutionService {

    private static final String ALREADY_RUNNING_MESSAGE = "데이터 수집·정제 작업이 이미 실행 중입니다.";
    private static final String INTERNAL_FAILURE_MESSAGE = "현재 단계를 처리하는 중 서버 오류가 발생했습니다.";

    private final Map<DataPipelineType, DataPipelineExecutionState> latestExecutions =
            new ConcurrentHashMap<>();

    private final DataPipelineRunner runner;
    private final DataPipelineExecutionLock executionLock;
    private final Executor executor;
    private final Clock clock;

    public DataPipelineExecutionService(
            DataPipelineRunner runner,
            DataPipelineExecutionLock executionLock,
            @Qualifier("dataPipelineExecutor") Executor executor,
            Clock clock
    ) {
        this.runner = runner;
        this.executionLock = executionLock;
        this.executor = executor;
        this.clock = clock;
    }

    public DataPipelineExecutionResponse start(DataPipelineType type) {
        DataPipelineExecutionLock.Lease lease = executionLock.tryAcquire()
                .orElseThrow(() -> new IngestAlreadyRunningException(ALREADY_RUNNING_MESSAGE));
        DataPipelineExecutionState execution = new DataPipelineExecutionState(
                UUID.randomUUID(),
                type,
                Instant.now(clock)
        );
        latestExecutions.put(type, execution);
        try {
            executor.execute(() -> execute(execution, type, lease));
        }
        catch (RuntimeException exception) {
            lease.close();
            execution.fail(null, INTERNAL_FAILURE_MESSAGE, null, Instant.now(clock));
            throw exception;
        }
        return execution.response();
    }

    public DataPipelineExecutionResponse findLatest(DataPipelineType type) {
        DataPipelineExecutionState execution = latestExecutions.get(type);
        if (execution == null) {
            return DataPipelineExecutionResponse.idle(type);
        }
        return execution.response();
    }

    private void execute(
            DataPipelineExecutionState execution,
            DataPipelineType type,
            DataPipelineExecutionLock.Lease lease
    ) {
        try (lease) {
            runner.run(type, progressListener(execution));
            execution.complete(Instant.now(clock));
        }
        catch (DataPipelinePartialFailureException exception) {
            execution.fail(
                    exception.getStep(),
                    exception.getMessage(),
                    exception.getServerResponse(),
                    Instant.now(clock)
            );
        }
        catch (RuntimeException exception) {
            DataPipelineStep currentStep = execution.currentStep();
            execution.fail(currentStep, INTERNAL_FAILURE_MESSAGE, null, Instant.now(clock));
            log.error(
                    "데이터 수집·정제 단계 실행에 실패했습니다: type={}, step={}",
                    type,
                    currentStep,
                    exception
            );
        }
    }

    private DataPipelineProgressListener progressListener(DataPipelineExecutionState execution) {
        return new DataPipelineProgressListener() {
            @Override
            public void started(DataPipelineStep step) {
                execution.startStep(step);
            }

            @Override
            public void completed(DataPipelineStep step) {
                execution.completeStep(step);
            }
        };
    }
}

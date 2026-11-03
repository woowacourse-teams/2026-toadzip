package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.collection.configuration.ExternalDataIngestProperties;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.exception.exception.IngestServiceKeyMissingException;
import com.toadzip.backend.ingest.pipeline.repository.IngestOwnershipLostException;
import com.toadzip.backend.ingest.exception.exception.LhAnnouncementUnavailableException;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineExecutionResponse;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineReadinessResponse;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 데이터 파이프라인 실행을 잠금을 잡고 시작해 작업 스레드에서 끝까지 돌리고, 결과를 완료·중단·실패로 남긴다.
 * 이력 조회는 {@link DataPipelineExecutionQueryService}, 끝나지 못한 실행 복구는 {@link DataPipelineExecutionRecovery}가 맡는다.
 */
@Slf4j
@Service
public class DataPipelineExecutionService {

    private static final String ALREADY_RUNNING_MESSAGE =
            "데이터 수집·정제 작업이 이미 실행 중입니다.";
    private static final String INTERNAL_FAILURE_MESSAGE =
            "현재 단계를 처리하는 중 서버 오류가 발생했습니다.";

    private final DataPipelineRunner runner;
    private final DataPipelineExecutionLock executionLock;
    private final DataPipelineExecutionStateService executionStateService;
    private final DataPipelineExecutionRecovery recovery;
    private final DataPipelineHeartbeat heartbeat;
    private final DataPipelineFailureRecorder failures;
    private final Executor executor;
    private final Clock clock;
    private final DataPipelineExecutionMapper executionMapper;
    private final ExternalDataIngestProperties ingestProperties;

    public DataPipelineExecutionService(
            DataPipelineRunner runner,
            DataPipelineExecutionLock executionLock,
            DataPipelineExecutionStateService executionStateService,
            DataPipelineExecutionRecovery recovery,
            DataPipelineHeartbeat heartbeat,
            DataPipelineFailureRecorder failures,
            @Qualifier("dataPipelineExecutor") Executor executor,
            Clock clock,
            DataPipelineExecutionMapper executionMapper,
            ExternalDataIngestProperties ingestProperties
    ) {
        this.runner = runner;
        this.executionLock = executionLock;
        this.executionStateService = executionStateService;
        this.recovery = recovery;
        this.heartbeat = heartbeat;
        this.failures = failures;
        this.executor = executor;
        this.clock = clock;
        this.executionMapper = executionMapper;
        this.ingestProperties = ingestProperties;
    }

    public DataPipelineReadinessResponse readiness() {
        return new DataPipelineReadinessResponse(ingestProperties.hasServiceKey());
    }

    /** 공공데이터포털을 호출하는 실행은 서버의 수집 키가 없으면 실행 기록을 만들기 전에 거절한다. */
    public DataPipelineExecutionResponse start(DataPipelineType type) {
        type.rejectIfRetired();
        if (type.requiresServiceKey() && !ingestProperties.hasServiceKey()) {
            throw new IngestServiceKeyMissingException();
        }
        UUID executionId = UUID.randomUUID();
        DataPipelineExecutionLock.Lease lease = executionLock.tryAcquire(executionId)
                .orElseThrow(() -> new IngestAlreadyRunningException(ALREADY_RUNNING_MESSAGE));
        DataPipelineExecution execution;
        try (var ignored = IngestExecutionScope.open(lease)) {
            Instant startedAt = Instant.now(clock);
            recovery.recoverBeforeStart(lease, startedAt);
            execution = executionStateService.create(executionId, type, startedAt);
        }
        catch (RuntimeException exception) {
            lease.close();
            throw exception;
        }
        DataPipelineExecutionResponse acceptedResponse = executionMapper.response(execution);
        ScheduledFuture<?> heartbeatTask;
        try {
            heartbeatTask = heartbeat.schedule(execution, lease);
        }
        catch (RuntimeException exception) {
            lease.close();
            failures.record(executionId, type, null, INTERNAL_FAILURE_MESSAGE, null);
            throw exception;
        }
        try {
            String traceId = MDC.get("traceId");
            executor.execute(() -> execute(executionId, type, lease, heartbeatTask, traceId));
        }
        catch (RuntimeException exception) {
            heartbeatTask.cancel(false);
            lease.close();
            failures.record(executionId, type, null, INTERNAL_FAILURE_MESSAGE, null);
            throw exception;
        }
        return acceptedResponse;
    }

    public DataPipelineExecutionResponse requestStop(UUID executionId) {
        return executionMapper.response(executionStateService.requestStop(executionId));
    }

    private void execute(
            UUID executionId,
            DataPipelineType type,
            DataPipelineExecutionLock.Lease lease,
            ScheduledFuture<?> heartbeatTask,
            String traceId
    ) {
        String previousTraceId = MDC.get("traceId");
        String previousExecutionId = MDC.get("executionId");
        setExecutionContext("traceId", traceId);
        MDC.put("executionId", executionId.toString());
        var monitor = new DataPipelineExecutionMonitor(executionId, executionStateService, clock);
        try (lease; var ignored = IngestExecutionScope.open(executionId, lease, monitor)) {
            lease.verifyHeld();
            runAndRecordOutcome(executionId, type);
        }
        catch (IngestOwnershipLostException exception) {
            failures.recordAtCurrentStep(executionId, type, exception.getMessage());
            log.warn("데이터 수집·정제 실행 소유권 소실: executionId={}", executionId);
        }
        catch (DataPipelinePartialFailureException exception) {
            failures.record(
                    executionId,
                    type,
                    exception.getStep(),
                    exception.getMessage(),
                    exception.getServerResponse()
            );
        }
        catch (LhAnnouncementUnavailableException exception) {
            DataPipelineStep failedStep = failures.currentStep(executionId);
            failures.record(executionId, type, failedStep, exception.getMessage(), null);
            log.warn("LH 공고 API 장애로 수집을 중단했습니다: type={}, step={}", type, failedStep);
        }
        catch (RuntimeException exception) {
            DataPipelineStep failedStep = failures.currentStep(executionId);
            failures.record(executionId, type, failedStep, INTERNAL_FAILURE_MESSAGE, null);
            log.error(
                    "데이터 수집·정제 단계 실행에 실패했습니다: type={}, step={}",
                    type,
                    failedStep,
                    exception
            );
        }
        finally {
            heartbeatTask.cancel(false);
            setExecutionContext("traceId", previousTraceId);
            setExecutionContext("executionId", previousExecutionId);
        }
    }

    private void runAndRecordOutcome(UUID executionId, DataPipelineType type) {
        try {
            runner.run(type, executionId);
        }
        catch (DataPipelineStoppedException exception) {
            executionStateService.stop(executionId, Instant.now(clock));
            return;
        }
        executionStateService.complete(executionId, Instant.now(clock));
    }

    private void setExecutionContext(String key, String value) {
        if (value == null) {
            MDC.remove(key);
            return;
        }
        MDC.put(key, value);
    }
}

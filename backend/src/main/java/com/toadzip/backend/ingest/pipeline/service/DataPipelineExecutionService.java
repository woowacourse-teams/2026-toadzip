package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.exception.exception.DataPipelineExecutionNotFoundException;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.exception.exception.IngestOwnershipLostException;
import com.toadzip.backend.ingest.exception.exception.LhAnnouncementUnavailableException;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineExecutionResponse;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
        return startExecution(type, null);
    }

    public DataPipelineExecutionResponse start(DataPipelineType type, String serviceKey) {
        if (type != DataPipelineType.COMPLEX_COLLECTION && type != DataPipelineType.ANNOUNCEMENT_COLLECTION
                && type != DataPipelineType.COMPLEX_SYNC && type != DataPipelineType.ANNOUNCEMENT_SYNC) {
            throw new InvalidIngestRequestException("수집 작업에만 서비스키를 입력할 수 있습니다.");
        }
        if (serviceKey == null || serviceKey.isBlank() || serviceKey.length() > 4096) {
            throw new InvalidIngestRequestException("서비스키는 1자 이상 4096자 이하로 입력해 주세요.");
        }
        return startExecution(type, serviceKey.strip());
    }

    private DataPipelineExecutionResponse startExecution(DataPipelineType type, String serviceKey) {
        UUID executionId = UUID.randomUUID();
        DataPipelineExecutionLock.Lease lease = executionLock.tryAcquire(executionId)
                .orElseThrow(() -> new IngestAlreadyRunningException(ALREADY_RUNNING_MESSAGE));
        DataPipelineExecution execution;
        try (var ignored = IngestExecutionScope.open(lease)) {
            Instant startedAt = Instant.now(clock);
            executionStateService.recoverInterruptedBefore(
                    startedAt.minus(EXECUTION_LEASE_TIMEOUT),
                    startedAt,
                    INTERRUPTED_FAILURE_MESSAGE
            );
            recoverTerminalCollections(startedAt);
            execution = executionStateService.create(executionId, type, startedAt);
        }
        catch (RuntimeException exception) {
            lease.close();
            throw exception;
        }
        DataPipelineExecutionResponse acceptedResponse = executionMapper.response(execution);
        ScheduledFuture<?> heartbeatTask;
        try {
            heartbeatTask = scheduleHeartbeat(execution, lease);
        }
        catch (RuntimeException exception) {
            lease.close();
            recordFailure(executionId, type, null, INTERNAL_FAILURE_MESSAGE, null);
            throw exception;
        }
        try {
            String traceId = MDC.get("traceId");
            executor.execute(() -> execute(executionId, type, lease, heartbeatTask, traceId, serviceKey));
        }
        catch (RuntimeException exception) {
            heartbeatTask.cancel(false);
            lease.close();
            recordFailure(executionId, type, null, INTERNAL_FAILURE_MESSAGE, null);
            throw exception;
        }
        return acceptedResponse;
    }

    public List<DataPipelineExecutionResponse> history(int page, int size) {
        recoverInterruptedExecutions();
        var request = PageRequest.of(
                page, size, Sort.by("id").descending()
        );
        return executionRepository.findAll(request).stream()
                .map(executionMapper::response)
                .toList();
    }

    public DataPipelineExecutionResponse findLatest(DataPipelineType type) {
        recoverInterruptedExecutions();
        return executionRepository.findFirstByTypeOrderByIdDesc(type)
                .map(executionMapper::response)
                .orElseGet(() -> DataPipelineExecutionResponse.idle(type));
    }

    public DataPipelineExecutionResponse requestStop(UUID executionId) {
        return executionMapper.response(executionStateService.requestStop(executionId));
    }

    public DataPipelineExecutionResponse find(UUID executionId) {
        recoverInterruptedExecutions();
        return executionRepository.findByExecutionId(executionId)
                .map(executionMapper::response)
                .orElseThrow(() -> new DataPipelineExecutionNotFoundException(
                        "데이터 파이프라인 실행을 찾을 수 없습니다: " + executionId
                ));
    }

    private void execute(
            UUID executionId,
            DataPipelineType type,
            DataPipelineExecutionLock.Lease lease,
            ScheduledFuture<?> heartbeatTask,
            String traceId,
            String serviceKey
    ) {
        String previousTraceId = MDC.get("traceId");
        String previousExecutionId = MDC.get("executionId");
        setExecutionContext("traceId", traceId);
        MDC.put("executionId", executionId.toString());
        var monitor = new DataPipelineExecutionMonitor(executionId, executionStateService, clock);
        try (lease; var ignored = IngestExecutionScope.open(lease, monitor, serviceKey)) {
            lease.verifyHeld();
            runAndRecordOutcome(executionId, type);
        }
        catch (IngestOwnershipLostException exception) {
            recordFailure(executionId, type, findCurrentStep(executionId), exception.getMessage(), null);
            log.warn("데이터 수집·정제 실행 소유권 소실: executionId={}", executionId);
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
        catch (LhAnnouncementUnavailableException exception) {
            DataPipelineStep failedStep = findCurrentStep(executionId);
            recordFailure(executionId, type, failedStep, exception.getMessage(), null);
            log.warn("LH 공고 API 장애로 수집을 중단했습니다: type={}, step={}", type, failedStep);
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

    private void recordFailure(
            UUID executionId,
            DataPipelineType type,
            DataPipelineStep failedStep,
            String message,
            String serverResponse
    ) {
        try {
            executionStateService.fail(
                    executionId,
                    failedStep,
                    message,
                    serverResponse,
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

    private ScheduledFuture<?> scheduleHeartbeat(
            DataPipelineExecution execution, DataPipelineExecutionLock.Lease lease
    ) {
        long intervalSeconds = HEARTBEAT_INTERVAL.toSeconds();
        return heartbeatExecutor.scheduleWithFixedDelay(
                () -> updateHeartbeat(execution, lease),
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS
        );
    }

    private void updateHeartbeat(DataPipelineExecution execution, DataPipelineExecutionLock.Lease lease) {
        try (var ignored = IngestExecutionScope.open(lease)) {
            lease.verifyHeld();
            executionStateService.heartbeat(execution.getId(), Instant.now(clock));
        }
        catch (IngestOwnershipLostException exception) {
            recordFailure(execution.getExecutionId(), execution.getType(),
                    findCurrentStep(execution.getExecutionId()), exception.getMessage(), null);
        }
        catch (RuntimeException exception) {
            log.error(
                    "데이터 수집·정제 실행 heartbeat 갱신에 실패했습니다: executionId={}",
                    execution.getExecutionId(),
                    exception
            );
        }
    }

    private void recoverInterruptedExecutions() {
        if (executionLock.isHeld()) {
            return;
        }
        Instant now = Instant.now(clock);
        try {
            executionStateService.recoverInterruptedBefore(
                    now.minus(EXECUTION_LEASE_TIMEOUT), now, INTERRUPTED_FAILURE_MESSAGE);
        }
        catch (RuntimeException exception) {
            log.error("중단된 데이터 파이프라인 실행을 복구하지 못했습니다.", exception);
        }
        recoverTerminalCollections(now);
    }

    private void recoverTerminalCollections(Instant now) {
        try {
            executionStateService.recoverTerminalCollections(now);
        }
        catch (RuntimeException exception) {
            log.error("종료된 실행의 미완료 수집 기록을 복구하지 못했습니다.", exception);
        }
    }
}

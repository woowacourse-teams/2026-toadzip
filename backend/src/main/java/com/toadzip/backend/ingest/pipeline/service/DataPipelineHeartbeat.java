package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.exception.exception.IngestOwnershipLostException;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** 실행 중인 작업이 살아 있음을 주기적으로 남긴다. 실행 잠금을 잃으면 그 실행을 실패로 끝낸다. */
@Slf4j
@Component
public class DataPipelineHeartbeat {

    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    private final ScheduledExecutorService heartbeatExecutor;
    private final DataPipelineExecutionStateService executionStateService;
    private final DataPipelineFailureRecorder failures;
    private final Clock clock;

    public DataPipelineHeartbeat(
            @Qualifier("dataPipelineHeartbeatExecutor") ScheduledExecutorService heartbeatExecutor,
            DataPipelineExecutionStateService executionStateService,
            DataPipelineFailureRecorder failures,
            Clock clock
    ) {
        this.heartbeatExecutor = heartbeatExecutor;
        this.executionStateService = executionStateService;
        this.failures = failures;
        this.clock = clock;
    }

    ScheduledFuture<?> schedule(DataPipelineExecution execution, DataPipelineExecutionLock.Lease lease) {
        long intervalSeconds = HEARTBEAT_INTERVAL.toSeconds();
        return heartbeatExecutor.scheduleWithFixedDelay(
                () -> update(execution, lease),
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS
        );
    }

    private void update(DataPipelineExecution execution, DataPipelineExecutionLock.Lease lease) {
        try (var ignored = IngestExecutionScope.open(lease)) {
            lease.verifyHeld();
            executionStateService.heartbeat(execution.getId(), Instant.now(clock));
        }
        catch (IngestOwnershipLostException exception) {
            failures.recordAtCurrentStep(execution.getExecutionId(), execution.getType(), exception.getMessage());
        }
        catch (RuntimeException exception) {
            log.error(
                    "데이터 수집·정제 실행 heartbeat 갱신에 실패했습니다: executionId={}",
                    execution.getExecutionId(),
                    exception
            );
        }
    }
}

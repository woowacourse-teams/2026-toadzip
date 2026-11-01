package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecoveryService;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 서버가 멈춰 끝나지 못한 실행과 그 실행에 딸린 수집 기록을 정리한다. heartbeat가 실행 임대 시간보다 오래 끊긴 실행을
 * 중단으로 보고, 실행이 끝났는데 열려 있는 수집과 실행에 연결되지 않은 수집을 닫는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataPipelineExecutionRecovery {

    private static final String INTERRUPTED_FAILURE_MESSAGE =
            "서버 실행이 중단되어 데이터 수집·정제 작업을 종료했습니다.";
    private static final Duration EXECUTION_LEASE_TIMEOUT = Duration.ofMinutes(2);

    private final DataPipelineExecutionLock executionLock;
    private final DataPipelineExecutionStateService executionStateService;
    private final SourceCollectionRecoveryService collectionRecovery;
    private final Clock clock;

    /** 새 실행을 만들기 전에 잡은 잠금으로 복구한다. 중단 실행 복구가 실패하면 새 실행을 시작하지 않는다. */
    void recoverBeforeStart(DataPipelineExecutionLock.Lease lease, Instant startedAt) {
        executionStateService.recoverInterruptedBefore(
                startedAt.minus(EXECUTION_LEASE_TIMEOUT),
                startedAt,
                INTERRUPTED_FAILURE_MESSAGE
        );
        recoverTerminalCollections(startedAt);
        collectionRecovery.recoverUnlinkedCollections(lease);
    }

    /** 조회할 때 실행 중인 작업이 없으면 복구한다. 다른 실행이 잠금을 잡고 있으면 건너뛴다. */
    void recoverIfIdle() {
        var recoveryLease = executionLock.tryAcquire();
        if (recoveryLease.isEmpty()) {
            return;
        }
        try (var lease = recoveryLease.orElseThrow()) {
            lease.verifyHeld();
            collectionRecovery.recoverUnlinkedCollections(lease);
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

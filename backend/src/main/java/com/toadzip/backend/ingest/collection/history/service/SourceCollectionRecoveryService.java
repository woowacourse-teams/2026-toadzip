package com.toadzip.backend.ingest.collection.history.service;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.repository.SourceCollectionRecordRepository;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class SourceCollectionRecoveryService {

    private static final List<String> SOURCES = Arrays.stream(CollectionSource.values()).map(Enum::name).toList();

    private final SourceCollectionRecordRepository records;
    private final Clock clock;
    private final TransactionTemplate independentTransaction;

    public SourceCollectionRecoveryService(
            SourceCollectionRecordRepository records, PlatformTransactionManager transactionManager, Clock clock
    ) {
        this.records = records;
        this.clock = clock;
        independentTransaction = new TransactionTemplate(transactionManager);
        independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 개별 수집도 전역 실행 잠금 안에서만 실행되므로, 이 잠금을 쥔 동안 남은 RUNNING은 중단된 수집이다. */
    public void recoverUnlinkedCollections(DataPipelineExecutionLock.Lease lease) {
        Instant recoveredAt = clock.instant();
        try {
            independentTransaction.executeWithoutResult(status -> recover(recoveredAt, lease));
        } catch (RuntimeException failure) {
            log.error("개별 수집의 중단 이력을 복구하지 못했습니다.", failure);
        }
    }

    private void recover(Instant recoveredAt, DataPipelineExecutionLock.Lease lease) {
        lease.verifyHeld();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                lease.verifyHeld();
            }
        });
        var interrupted = records.findUnlinkedRunningForUpdate(SOURCES, recoveredAt);
        interrupted.forEach(record -> record.fail(recoveredAt, "InterruptedCollection",
                "개별 수집 실행이 중단되어 결과를 저장하지 못했습니다. 원천을 확인한 뒤 다시 수집해 주세요."));
        records.flush();
    }
}

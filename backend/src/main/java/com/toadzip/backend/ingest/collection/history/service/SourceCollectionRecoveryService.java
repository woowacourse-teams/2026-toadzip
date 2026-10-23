package com.toadzip.backend.ingest.collection.history.service;

import com.toadzip.backend.global.persistence.PostgresAdvisoryLock;
import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.repository.SourceCollectionRecordRepository;
import com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock;
import com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock.Operation;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    private static final Map<Operation, List<CollectionSource>> COLLECTION_OPERATIONS = Map.of(
            Operation.MYHOME_COMPLEX_COLLECTION, List.of(CollectionSource.MYHOME_COMPLEX),
            Operation.MYHOME_ANNOUNCEMENT_COLLECTION, List.of(CollectionSource.MYHOME_ANNOUNCEMENT),
            Operation.LH_LEASE_CATALOG_COLLECTION, List.of(CollectionSource.LH_LEASE_CATALOG),
            Operation.LH_ANNOUNCEMENT_COLLECTION, List.of(CollectionSource.LH_ANNOUNCEMENT_CATALOG,
                    CollectionSource.LH_ANNOUNCEMENT_SUPPLY, CollectionSource.LH_ANNOUNCEMENT_DETAIL));

    private final IngestOperationLock operationLock;
    private final SourceCollectionRecordRepository records;
    private final Clock clock;
    private final TransactionTemplate independentTransaction;

    public SourceCollectionRecoveryService(
            IngestOperationLock operationLock, SourceCollectionRecordRepository records,
            PlatformTransactionManager transactionManager, Clock clock
    ) {
        this.operationLock = operationLock;
        this.records = records;
        this.clock = clock;
        independentTransaction = new TransactionTemplate(transactionManager);
        independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void recoverUnlinkedCollections() {
        Instant recoveredAt = clock.instant();
        COLLECTION_OPERATIONS.forEach((operation, sources) -> {
            try {
                operationLock.tryRunWithLease(operation, lease -> recover(sources, recoveredAt, lease));
            } catch (RuntimeException failure) {
                log.error("개별 수집의 중단 이력을 복구하지 못했습니다: operation={}", operation, failure);
            }
        });
    }

    private int recover(List<CollectionSource> sources, Instant recoveredAt, PostgresAdvisoryLock.Lease lease) {
        return independentTransaction.execute(status -> {
            verifyHeld(lease);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    verifyHeld(lease);
                }
            });
            var sourceNames = sources.stream().map(CollectionSource::name).toList();
            var interrupted = records.findUnlinkedRunningForUpdate(sourceNames, recoveredAt);
            interrupted.forEach(record -> record.fail(recoveredAt, "InterruptedCollection",
                    "개별 수집 실행이 중단되어 결과를 저장하지 못했습니다. 원천을 확인한 뒤 다시 수집해 주세요."));
            records.flush();
            return interrupted.size();
        });
    }

    private void verifyHeld(PostgresAdvisoryLock.Lease lease) {
        try {
            lease.verifyHeld();
        } catch (SQLException failure) {
            throw new IllegalStateException("수집 이력 복구의 작업별 잠금 소유권을 확인하지 못했습니다.", failure);
        }
    }
}

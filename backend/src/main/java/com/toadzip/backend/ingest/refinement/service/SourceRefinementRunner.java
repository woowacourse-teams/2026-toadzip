package com.toadzip.backend.ingest.refinement.service;

import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 원천을 정제하는 실행 하나를 시작한다. 정제 규칙은 기관 정제기가, 원천별 트랜잭션·실패 기록은 여기가 맡는다. */
@Component
public class SourceRefinementRunner {

    private final TransactionTemplate transactions;
    private final IngestWriteOwnershipGuard ownershipGuard;

    public SourceRefinementRunner(PlatformTransactionManager transactionManager,
            IngestWriteOwnershipGuard ownershipGuard) {
        this.ownershipGuard = ownershipGuard;
        transactions = new TransactionTemplate(transactionManager);
        // 원천마다 따로 커밋해 한 원천의 실패가 앞서 정제한 공고를 되돌리지 않게 한다.
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <O extends Enum<O>> SourceRefinementRun<O> begin(String label, Class<O> outcomeType) {
        return new SourceRefinementRun<>(label, outcomeType, transactions, ownershipGuard);
    }
}

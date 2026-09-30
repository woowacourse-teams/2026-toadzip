package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock.Lease;
import com.toadzip.backend.ingest.pipeline.repository.IngestExecutionOwnershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class IngestWriteOwnershipGuard {

    private final IngestExecutionOwnershipRepository ownershipRepository;

    public IngestWriteOwnershipGuard(IngestExecutionOwnershipRepository ownershipRepository) {
        this.ownershipRepository = ownershipRepository;
    }

    public void verifyWrite() {
        IngestExecutionScope.current().ifPresent(this::verifyWrite);
    }

    private void verifyWrite(Lease lease) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("실행 소유권은 쓰기 트랜잭션 안에서 확인해야 합니다.");
        }
        boolean alreadyVerified = TransactionSynchronizationManager.getSynchronizations().stream()
                .anyMatch(synchronization -> synchronization instanceof OwnershipSynchronization ownership
                        && ownership.lease() == lease);
        if (alreadyVerified) {
            return;
        }
        lease.verifyHeld();
        ownershipRepository.lockAndVerify(lease.ownerId(), lease.generation());
        TransactionSynchronizationManager.registerSynchronization(new OwnershipSynchronization(lease));
    }

    private record OwnershipSynchronization(Lease lease) implements TransactionSynchronization {

        @Override
        public void beforeCommit(boolean readOnly) {
            lease.verifyHeld();
        }
    }
}

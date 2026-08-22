package com.toadzip.backend.ingest.repository;

import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.toadzip.backend.ingest.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.domain.ExternalDataSnapshot;

@Repository
public class ExternalDataCollectionStore {

    private final ExternalDataSnapshotRepository snapshotRepository;

    private final ExternalDataCollectionFailureRepository failureRepository;

    public ExternalDataCollectionStore(
            ExternalDataSnapshotRepository snapshotRepository,
            ExternalDataCollectionFailureRepository failureRepository
    ) {
        this.snapshotRepository = snapshotRepository;
        this.failureRepository = failureRepository;
    }

    @Transactional
    public void storeSnapshots(List<ExternalDataSnapshot> snapshots) {
        snapshotRepository.saveAll(snapshots);
    }

    @Transactional
    public void storeFailure(ExternalDataCollectionFailure failure) {
        failureRepository.save(failure);
    }
}

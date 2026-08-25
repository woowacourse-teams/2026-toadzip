package com.toadzip.backend.ingest.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.domain.ExternalDataFailureStatus;

public interface ExternalDataCollectionFailureRepository extends JpaRepository<ExternalDataCollectionFailure, Long> {

    List<ExternalDataCollectionFailure> findAllBySourceAndRequestDescriptionAndStatus(
            ExternalDataSource source,
            String requestDescription,
            ExternalDataFailureStatus status
    );
}

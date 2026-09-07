package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.collection.domain.ExternalDataFailureStatus;
import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalDataCollectionFailureRepository extends JpaRepository<ExternalDataCollectionFailure, Long> {

    List<ExternalDataCollectionFailure> findAllBySourceAndRequestDescriptionAndStatus(
            ExternalDataSource source,
            String requestDescription,
            ExternalDataFailureStatus status
    );
}

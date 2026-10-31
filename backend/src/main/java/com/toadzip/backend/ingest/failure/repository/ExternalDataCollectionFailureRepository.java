package com.toadzip.backend.ingest.failure.repository;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.failure.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExternalDataCollectionFailureRepository extends JpaRepository<ExternalDataCollectionFailure, Long> {

    List<ExternalDataCollectionFailure> findAllBySourceAndSourceKeyAndStatus(
            ExternalDataSource source,
            String requestDescription,
            IngestFailureStatus status
    );

    List<ExternalDataCollectionFailure> findAllBySourceAndSourceKeyInAndStatus(
            ExternalDataSource source,
            Collection<String> requestDescriptions,
            IngestFailureStatus status
    );

    List<ExternalDataCollectionFailure> findAllBySourceAndStatusAndSourceKeyStartingWith(
            ExternalDataSource source,
            IngestFailureStatus status,
            String requestDescriptionPrefix
    );

    Optional<ExternalDataCollectionFailure> findFirstBySourceAndSourceKeyOrderByIdDesc(
            ExternalDataSource source,
            String requestDescription
    );

    @Query("""
            select failure
            from ExternalDataCollectionFailure failure
            where failure.status = com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING
              and failure.id = (
                  select max(latest.id)
                  from ExternalDataCollectionFailure latest
                  where latest.source = failure.source
                    and latest.sourceKey = failure.sourceKey
              )
            order by failure.lastOccurredAt desc, failure.id desc
            """)
    List<ExternalDataCollectionFailure> findLatestPendingByRequest(Pageable pageable);

    List<ExternalDataCollectionFailure> findAllByOrderByLastOccurredAtDescIdDesc(Pageable pageable);
}

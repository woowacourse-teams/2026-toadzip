package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.collection.domain.ExternalDataFailureStatus;
import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExternalDataCollectionFailureRepository extends JpaRepository<ExternalDataCollectionFailure, Long> {

    List<ExternalDataCollectionFailure> findAllBySourceAndRequestDescriptionAndStatus(
            ExternalDataSource source,
            String requestDescription,
            ExternalDataFailureStatus status
    );

    List<ExternalDataCollectionFailure> findAllBySourceAndStatusAndRequestDescriptionStartingWith(
            ExternalDataSource source,
            ExternalDataFailureStatus status,
            String requestDescriptionPrefix
    );

    Optional<ExternalDataCollectionFailure> findFirstBySourceAndRequestDescriptionOrderByIdDesc(
            ExternalDataSource source,
            String requestDescription
    );

    @Query("""
            select failure
            from ExternalDataCollectionFailure failure
            where failure.status = com.toadzip.backend.ingest.collection.domain.ExternalDataFailureStatus.PENDING
              and failure.id = (
                  select max(latest.id)
                  from ExternalDataCollectionFailure latest
                  where latest.source = failure.source
                    and latest.requestDescription = failure.requestDescription
              )
            order by failure.lastOccurredAt desc, failure.id desc
            """)
    List<ExternalDataCollectionFailure> findLatestPendingByRequest(Pageable pageable);

    List<ExternalDataCollectionFailure> findAllByOrderByLastOccurredAtDescIdDesc(Pageable pageable);
}

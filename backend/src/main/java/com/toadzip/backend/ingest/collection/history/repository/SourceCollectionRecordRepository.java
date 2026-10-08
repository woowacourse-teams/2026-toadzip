package com.toadzip.backend.ingest.collection.history.repository;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.domain.CollectionStatus;
import com.toadzip.backend.ingest.collection.history.domain.SourceCollectionRecord;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface SourceCollectionRecordRepository extends JpaRepository<SourceCollectionRecord, UUID> {

    boolean existsBySourceAndStatus(CollectionSource source, CollectionStatus status);

    List<SourceCollectionRecord> findAllByExecutionIdAndSource(UUID executionId, CollectionSource source);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<SourceCollectionRecord> findAllByExecutionIdAndStatusOrderByStartedAtAscIdAsc(
            UUID executionId, CollectionStatus status);
}

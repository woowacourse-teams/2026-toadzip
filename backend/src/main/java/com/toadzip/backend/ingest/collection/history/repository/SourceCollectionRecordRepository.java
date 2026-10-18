package com.toadzip.backend.ingest.collection.history.repository;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.domain.CollectionStatus;
import com.toadzip.backend.ingest.collection.history.domain.SourceCollectionRecord;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SourceCollectionRecordRepository extends JpaRepository<SourceCollectionRecord, UUID> {

    boolean existsBySourceAndStatus(CollectionSource source, CollectionStatus status);

    List<SourceCollectionRecord> findAllByExecutionIdAndSource(UUID executionId, CollectionSource source);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<SourceCollectionRecord> findAllByExecutionIdAndStatusOrderByStartedAtAscIdAsc(
            UUID executionId, CollectionStatus status);

    @Query(value = """
            SELECT record.* FROM source_collection_records record
            WHERE record.status = 'RUNNING'
              AND record.source IN :sources
              AND record.started_at <= :recoveredAt
              AND NOT EXISTS (
                  SELECT 1 FROM data_pipeline_executions execution
                  WHERE execution.execution_id = record.execution_id
              )
            ORDER BY record.started_at ASC, record.id ASC
            FOR UPDATE OF record
            """, nativeQuery = true)
    List<SourceCollectionRecord> findUnlinkedRunningForUpdate(
            @Param("sources") List<String> sources, @Param("recoveredAt") Instant recoveredAt);
}

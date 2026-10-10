package com.toadzip.backend.ingest.pipeline.repository;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DataPipelineExecutionRepository
        extends JpaRepository<DataPipelineExecution, Long> {

    org.springframework.data.domain.Page<DataPipelineExecution> findByTypeIn(
            java.util.Collection<DataPipelineType> types, org.springframework.data.domain.Pageable pageable);

    Optional<DataPipelineExecution> findFirstByTypeOrderByIdDesc(
            DataPipelineType type
    );

    Optional<DataPipelineExecution> findByExecutionId(UUID executionId);

    boolean existsByExecutionIdAndStopRequestedTrue(UUID executionId);

    @Modifying
    @Query("""
            update DataPipelineExecution execution
            set execution.lastRequestDescription = :description,
                execution.lastProgressAt = :now
            where execution.executionId = :executionId
              and execution.status = com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus.RUNNING
            """)
    int recordRequestStarted(UUID executionId, String description, Instant now);

    @Modifying
    @Query("""
            update DataPipelineExecution execution
            set execution.externalRequestCount = execution.externalRequestCount + 1,
                execution.lastProgressAt = :now
            where execution.executionId = :executionId
              and execution.status = com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus.RUNNING
            """)
    int recordRequestFinished(UUID executionId, Instant now);


    @Modifying
    @Query("""
            update DataPipelineExecution execution
            set execution.workProgress = :progress
            where execution.executionId = :executionId
              and execution.status = com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus.RUNNING
            """)
    int recordWorkProgress(UUID executionId, String progress);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select execution from DataPipelineExecution execution where execution.executionId = :executionId")
    Optional<DataPipelineExecution> findByExecutionIdForUpdate(@Param("executionId") UUID executionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select execution from DataPipelineExecution execution
            where execution.status = com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus.RUNNING
              and execution.heartbeatAt < :cutoff
            order by execution.id asc
            """)
    List<DataPipelineExecution> findInterruptedBeforeForUpdate(@Param("cutoff") Instant cutoff);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select execution from DataPipelineExecution execution
            where execution.status <> com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus.RUNNING
              and exists (
                  select record.id from SourceCollectionRecord record
                  where record.executionId = execution.executionId
                    and record.status = com.toadzip.backend.ingest.collection.history.domain.CollectionStatus.RUNNING
              )
            order by execution.id asc
            """)
    List<DataPipelineExecution> findTerminalWithRunningCollectionsForUpdate();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select execution from DataPipelineExecution execution
            where execution.executionId = :executionId
              and execution.status = com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus.RUNNING
              and execution.heartbeatAt < :cutoff
            """)
    Optional<DataPipelineExecution> findInterruptedForUpdate(
            @Param("executionId") UUID executionId,
            @Param("cutoff") Instant cutoff
    );

    @Modifying
    @Transactional
    @Query("""
            update DataPipelineExecution execution
            set execution.heartbeatAt = :heartbeatAt
            where execution.id = :executionId
              and execution.status = com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus.RUNNING
            """)
    int updateHeartbeat(
            @Param("executionId") Long executionId,
            @Param("heartbeatAt") Instant heartbeatAt
    );
}

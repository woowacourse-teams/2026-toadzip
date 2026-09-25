package com.toadzip.backend.ingest.pipeline.repository;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DataPipelineExecutionRepository
        extends JpaRepository<DataPipelineExecution, Long> {

    Optional<DataPipelineExecution> findFirstByTypeOrderByIdDesc(
            DataPipelineType type
    );

    Optional<DataPipelineExecution> findFirstByTypeAndScheduledAtOrderByIdDesc(
            DataPipelineType type,
            Instant scheduledAt
    );

    Optional<DataPipelineExecution> findFirstByTypeAndScheduledAtBeforeOrderByScheduledAtDesc(
            DataPipelineType type,
            Instant scheduledAt
    );

    boolean existsByTypeAndStartedAtBefore(
            DataPipelineType type,
            Instant startedAt
    );

    Optional<DataPipelineExecution> findFirstByTypeAndUpstreamExecutionIdOrderByIdDesc(
            DataPipelineType type,
            UUID upstreamExecutionId
    );

    @Query("""
            select collection
            from DataPipelineExecution collection
            where collection.type = :collectionType
              and collection.status = :status
              and collection.scheduledAt < :scheduledAt
              and not exists (
                  select refinement.id
                  from DataPipelineExecution refinement
                  where refinement.type = :refinementType
                    and refinement.upstreamExecutionId = collection.executionId
              )
            order by collection.scheduledAt asc, collection.id asc
            """)
    List<DataPipelineExecution> findCompletedWithoutRefinementBefore(
            @Param("collectionType") DataPipelineType collectionType,
            @Param("refinementType") DataPipelineType refinementType,
            @Param("status") DataPipelineExecutionStatus status,
            @Param("scheduledAt") Instant scheduledAt,
            Pageable pageable
    );

    Optional<DataPipelineExecution> findByExecutionId(UUID executionId);

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

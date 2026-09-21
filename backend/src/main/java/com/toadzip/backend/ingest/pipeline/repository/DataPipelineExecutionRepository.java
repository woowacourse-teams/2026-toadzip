package com.toadzip.backend.ingest.pipeline.repository;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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

    Optional<DataPipelineExecution> findFirstByTypeAndUpstreamExecutionIdOrderByIdDesc(
            DataPipelineType type,
            UUID upstreamExecutionId
    );

    Optional<DataPipelineExecution> findByExecutionId(UUID executionId);

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

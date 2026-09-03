package com.toadzip.backend.ingest.repository;

import com.toadzip.backend.ingest.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.domain.DataPipelineType;
import java.time.Instant;
import java.util.Optional;
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

    @Modifying
    @Transactional
    @Query("""
            update DataPipelineExecution execution
            set execution.heartbeatAt = :heartbeatAt
            where execution.id = :executionId
              and execution.status = com.toadzip.backend.ingest.domain.DataPipelineExecutionStatus.RUNNING
            """)
    int updateHeartbeat(
            @Param("executionId") Long executionId,
            @Param("heartbeatAt") Instant heartbeatAt
    );
}

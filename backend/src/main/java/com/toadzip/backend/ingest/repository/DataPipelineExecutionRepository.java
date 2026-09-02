package com.toadzip.backend.ingest.repository;

import com.toadzip.backend.ingest.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.domain.DataPipelineType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataPipelineExecutionRepository
        extends JpaRepository<DataPipelineExecution, Long> {

    Optional<DataPipelineExecution> findFirstByTypeOrderByStartedAtDescIdDesc(
            DataPipelineType type
    );
}

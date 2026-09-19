package com.toadzip.backend.ingest.mapping.repository;

import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailure;
import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MyHomeComplexMappingFailureRepository
        extends JpaRepository<MyHomeComplexMappingFailure, Long> {

    List<MyHomeComplexMappingFailure> findAllByOrderBySourceKeyAsc();

    List<MyHomeComplexMappingFailure> findAllByStatusOrderBySourceKeyAsc(
            IngestFailureStatus status
    );

    List<MyHomeComplexMappingFailure> findAllBySourceComplexIdentifier(
            String sourceComplexIdentifier
    );
}

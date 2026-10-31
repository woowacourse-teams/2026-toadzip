package com.toadzip.backend.ingest.mapping.repository;

import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailure;
import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
import com.toadzip.backend.ingest.failure.repository.IngestFailureRepository;
import java.util.List;
import org.springframework.data.domain.Pageable;

public interface MyHomeComplexMappingFailureRepository
        extends IngestFailureRepository<MyHomeComplexMappingFailure> {

    List<MyHomeComplexMappingFailure> findAllByOrderBySourceKeyAscIdAsc(Pageable pageable);

    List<MyHomeComplexMappingFailure> findAllByStatusOrderBySourceKeyAsc(
            IngestFailureStatus status
    );

    List<MyHomeComplexMappingFailure> findAllByStatusOrderBySourceKeyAscIdAsc(
            IngestFailureStatus status,
            Pageable pageable
    );

}

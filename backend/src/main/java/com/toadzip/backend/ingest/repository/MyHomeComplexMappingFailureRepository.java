package com.toadzip.backend.ingest.repository;

import com.toadzip.backend.ingest.domain.MyHomeComplexMappingFailure;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MyHomeComplexMappingFailureRepository
        extends JpaRepository<MyHomeComplexMappingFailure, Long> {

    List<MyHomeComplexMappingFailure> findAllByOrderBySourceKeyAsc();

    void deleteAllBySourceComplexIdentifier(String sourceComplexIdentifier);
}

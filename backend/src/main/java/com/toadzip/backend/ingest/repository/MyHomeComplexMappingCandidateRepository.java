package com.toadzip.backend.ingest.repository;

import com.toadzip.backend.ingest.domain.MyHomeComplexMappingCandidate;
import com.toadzip.backend.ingest.domain.MyHomeComplexMappingCandidateStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MyHomeComplexMappingCandidateRepository
        extends JpaRepository<MyHomeComplexMappingCandidate, Long> {

    List<MyHomeComplexMappingCandidate> findAllByStatusInOrderByIdAsc(
            Collection<MyHomeComplexMappingCandidateStatus> statuses,
            Pageable pageable
    );

    Optional<MyHomeComplexMappingCandidate> findFirstBySourceRoadAddressAndLatitudeIsNotNullOrderByIdAsc(
            String sourceRoadAddress
    );
}

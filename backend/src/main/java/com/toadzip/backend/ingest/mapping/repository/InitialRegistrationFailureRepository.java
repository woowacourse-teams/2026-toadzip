package com.toadzip.backend.ingest.mapping.repository;

import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationFailure;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InitialRegistrationFailureRepository extends JpaRepository<InitialRegistrationFailure, Long> {
    Page<InitialRegistrationFailure> findBySourceComplexIdentifierOrderByFailedAtDescIdDesc(
            String identifier, Pageable pageable);
}

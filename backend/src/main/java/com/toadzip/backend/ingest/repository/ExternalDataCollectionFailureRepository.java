package com.toadzip.backend.ingest.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.toadzip.backend.ingest.domain.ExternalDataCollectionFailure;

public interface ExternalDataCollectionFailureRepository extends JpaRepository<ExternalDataCollectionFailure, Long> {
}

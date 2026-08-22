package com.toadzip.backend.ingest.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.toadzip.backend.ingest.domain.ExternalApiCollectionFailure;

public interface ExternalApiCollectionFailureRepository extends JpaRepository<ExternalApiCollectionFailure, Long> {
}

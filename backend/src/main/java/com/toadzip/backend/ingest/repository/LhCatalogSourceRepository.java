package com.toadzip.backend.ingest.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.toadzip.backend.ingest.domain.LhCatalogSource;

public interface LhCatalogSourceRepository extends JpaRepository<LhCatalogSource, Long> {
}

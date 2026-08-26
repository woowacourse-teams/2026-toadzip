package com.toadzip.backend.ingest.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.domain.LhAnnouncementCollectionCheckpoint;

public interface LhAnnouncementCollectionCheckpointRepository
        extends JpaRepository<LhAnnouncementCollectionCheckpoint, Long> {

    boolean existsBySourceAndRequestHash(
            ExternalDataSource source,
            String requestHash
    );

    boolean existsBySourceAndPanId(ExternalDataSource source, String panId);
}

package com.toadzip.backend.ingest.enrichment.repository;

import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailure;
import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LhAnnouncementEnrichmentFailureRepository
        extends JpaRepository<LhAnnouncementEnrichmentFailure, Long> {

    List<LhAnnouncementEnrichmentFailure> findAllByOrderBySourceKeyAscIdAsc(Pageable pageable);

    List<LhAnnouncementEnrichmentFailure> findAllByStatusOrderBySourceKeyAsc(
            IngestFailureStatus status
    );

    List<LhAnnouncementEnrichmentFailure> findAllByStatusOrderBySourceKeyAscIdAsc(
            IngestFailureStatus status,
            Pageable pageable
    );
}

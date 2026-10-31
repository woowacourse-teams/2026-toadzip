package com.toadzip.backend.ingest.enrichment.repository;

import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailure;
import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
import com.toadzip.backend.ingest.failure.repository.IngestFailureRepository;
import java.util.List;
import org.springframework.data.domain.Pageable;

public interface LhAnnouncementEnrichmentFailureRepository
        extends IngestFailureRepository<LhAnnouncementEnrichmentFailure> {

    List<LhAnnouncementEnrichmentFailure> findAllByOrderBySourceKeyAscIdAsc(Pageable pageable);

    List<LhAnnouncementEnrichmentFailure> findAllByStatusOrderBySourceKeyAsc(
            IngestFailureStatus status
    );

    List<LhAnnouncementEnrichmentFailure> findAllByStatus(IngestFailureStatus status);

    List<LhAnnouncementEnrichmentFailure> findAllBySourceAnnouncementIdentifier(String sourceAnnouncementIdentifier);

    List<LhAnnouncementEnrichmentFailure> findAllByStatusOrderBySourceKeyAscIdAsc(
            IngestFailureStatus status,
            Pageable pageable
    );
}

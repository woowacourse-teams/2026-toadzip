package com.toadzip.backend.ingest.enrichment.repository;

import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailure;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LhAnnouncementEnrichmentFailureRepository
        extends JpaRepository<LhAnnouncementEnrichmentFailure, Long> {

    List<LhAnnouncementEnrichmentFailure> findAllByOrderBySourceKeyAsc();
}

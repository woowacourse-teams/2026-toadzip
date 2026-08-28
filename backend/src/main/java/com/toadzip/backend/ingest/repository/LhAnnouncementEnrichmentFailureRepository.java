package com.toadzip.backend.ingest.repository;

import com.toadzip.backend.ingest.domain.LhAnnouncementEnrichmentFailure;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LhAnnouncementEnrichmentFailureRepository
        extends JpaRepository<LhAnnouncementEnrichmentFailure, Long> {

    List<LhAnnouncementEnrichmentFailure> findAllByOrderBySourceKeyAsc();
}

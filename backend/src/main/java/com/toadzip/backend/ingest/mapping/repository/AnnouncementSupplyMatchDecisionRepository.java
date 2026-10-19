package com.toadzip.backend.ingest.mapping.repository;

import com.toadzip.backend.ingest.mapping.domain.AnnouncementSupplyMatchDecision;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnnouncementSupplyMatchDecisionRepository
        extends JpaRepository<AnnouncementSupplyMatchDecision, Long> {
    Optional<AnnouncementSupplyMatchDecision> findByRowIdentifier(String rowIdentifier);
}

package com.toadzip.backend.ingest.enrichment.repository;

import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdComplexLink;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LhHouseholdComplexLinkRepository extends JpaRepository<LhHouseholdComplexLink, Long> {
    Optional<LhHouseholdComplexLink> findBySourceKey(String sourceKey);
}

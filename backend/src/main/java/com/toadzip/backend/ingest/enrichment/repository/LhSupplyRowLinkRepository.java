package com.toadzip.backend.ingest.enrichment.repository;

import com.toadzip.backend.ingest.enrichment.domain.LhSupplyRowLink;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LhSupplyRowLinkRepository extends JpaRepository<LhSupplyRowLink, Long> {
    List<LhSupplyRowLink> findAllBySourceAnnouncementIdentifierOrderById(String sourceAnnouncementIdentifier);

}

package com.toadzip.backend.ingest.mapping.repository;

import com.toadzip.backend.ingest.mapping.domain.LhSupplyRowLink;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LhSupplyRowLinkRepository extends JpaRepository<LhSupplyRowLink, Long> {
    List<LhSupplyRowLink> findAllBySourceAnnouncementIdentifierOrderById(String sourceAnnouncementIdentifier);

    Optional<LhSupplyRowLink> findBySourceAnnouncementIdentifierAndLhSupplyRowIdentifier(
            String sourceAnnouncementIdentifier, String lhSupplyRowIdentifier);
}

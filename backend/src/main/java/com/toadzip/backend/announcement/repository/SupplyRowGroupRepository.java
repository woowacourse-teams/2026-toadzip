package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyRowGroup;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplyRowGroupRepository extends JpaRepository<SupplyRowGroup, Long> {
    Optional<SupplyRowGroup> findBySourceRowIdentifier(String identifier);
    List<SupplyRowGroup> findAllByAnnouncement(Announcement announcement);
}

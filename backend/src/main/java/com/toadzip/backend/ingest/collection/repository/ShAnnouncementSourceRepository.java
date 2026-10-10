package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.ShAnnouncementSource;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShAnnouncementSourceRepository extends JpaRepository<ShAnnouncementSource, Long> {

    Optional<ShAnnouncementSource> findBySourceKey(String sourceKey);
}

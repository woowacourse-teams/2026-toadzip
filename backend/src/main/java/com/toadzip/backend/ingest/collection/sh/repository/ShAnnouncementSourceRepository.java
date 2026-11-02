package com.toadzip.backend.ingest.collection.sh.repository;

import com.toadzip.backend.ingest.collection.sh.domain.ShAnnouncementSource;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShAnnouncementSourceRepository extends JpaRepository<ShAnnouncementSource, Long> {

    Optional<ShAnnouncementSource> findBySourceKey(String sourceKey);
}

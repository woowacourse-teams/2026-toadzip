package com.toadzip.backend.ingest.collection.gh.repository;

import com.toadzip.backend.ingest.collection.gh.domain.GhAnnouncementSource;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GhAnnouncementSourceRepository extends JpaRepository<GhAnnouncementSource, Long> {

    Optional<GhAnnouncementSource> findBySourceKey(String sourceKey);
}

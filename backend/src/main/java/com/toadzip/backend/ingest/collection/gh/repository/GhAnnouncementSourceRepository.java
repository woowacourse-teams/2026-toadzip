package com.toadzip.backend.ingest.collection.gh.repository;

import com.toadzip.backend.ingest.collection.gh.domain.GhAnnouncementSource;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GhAnnouncementSourceRepository extends JpaRepository<GhAnnouncementSource, Long> {

    Optional<GhAnnouncementSource> findBySourceKey(String sourceKey);

    List<GhAnnouncementSource> findAllByPbancNoIn(Collection<String> pbancNos);
}

package com.toadzip.backend.ingest.collection.sh.repository;

import com.toadzip.backend.ingest.collection.sh.domain.ShAnnouncementSource;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShAnnouncementSourceRepository extends JpaRepository<ShAnnouncementSource, Long> {

    Optional<ShAnnouncementSource> findBySourceKey(String sourceKey);

    List<ShAnnouncementSource> findAllBySeqIn(Collection<String> seqs);
}

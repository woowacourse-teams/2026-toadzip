package com.toadzip.backend.ingest.sh.collection.repository;

import com.toadzip.backend.ingest.sh.collection.domain.ShAnnouncementSource;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShAnnouncementSourceRepository extends JpaRepository<ShAnnouncementSource, Long> {

    Optional<ShAnnouncementSource> findBySourceKey(String sourceKey);

    List<ShAnnouncementSource> findAllBySeqIn(Collection<String> seqs);
}

package com.toadzip.backend.ingest.collection.myhome.announcement.repository;

import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSource;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MyHomeAnnouncementCollectionRepository extends JpaRepository<MyHomeAnnouncementSource, Long> {

    @Override
    @EntityGraph(attributePaths = "rows")
    List<MyHomeAnnouncementSource> findAll();

    @EntityGraph(attributePaths = "rows")
    List<MyHomeAnnouncementSource> findAllByPblancIdIn(Collection<String> pblancIds);
}

package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MyHomeAnnouncementSourceRepository extends JpaRepository<MyHomeAnnouncementSource, Long> {

    List<MyHomeAnnouncementSource> findAllBySourceKeyIn(Collection<String> sourceKeys);

    List<MyHomeAnnouncementSource> findAllByOrderByIdAsc();

    List<MyHomeAnnouncementSource> findAllByPblancIdOrderByIdAsc(String pblancId);

    @Query("""
            select source
            from MyHomeAnnouncementSource source
            where source.active = true
              and (source.lastSeenRunId is null or source.lastSeenRunId <> :runId)
            """)
    List<MyHomeAnnouncementSource> findAllActiveNotSeenInRun(String runId);

    List<MyHomeAnnouncementSource> findByIdGreaterThanOrderByIdAsc(Long id, Pageable pageable);

    @Query("select coalesce(max(source.sourceOrder), -1) from MyHomeAnnouncementSource source")
    int findMaxSourceOrder();
}

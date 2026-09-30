package com.toadzip.backend.ingest.mapping.repository;

import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailure;
import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MyHomeAnnouncementMappingFailureRepository
        extends JpaRepository<MyHomeAnnouncementMappingFailure, Long> {

    List<MyHomeAnnouncementMappingFailure> findAllByOrderBySourceKeyAscIdAsc(Pageable pageable);

    List<MyHomeAnnouncementMappingFailure> findAllByStatusOrderBySourceKeyAsc(
            IngestFailureStatus status
    );

    List<MyHomeAnnouncementMappingFailure> findAllByStatus(IngestFailureStatus status);

    List<MyHomeAnnouncementMappingFailure> findAllBySourceKeyIn(Collection<String> sourceKeys);

    List<MyHomeAnnouncementMappingFailure> findAllByStatusOrderBySourceKeyAscIdAsc(
            IngestFailureStatus status,
            Pageable pageable
    );
}

package com.toadzip.backend.ingest.mapping.repository;

import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailure;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MyHomeAnnouncementMappingFailureRepository
        extends JpaRepository<MyHomeAnnouncementMappingFailure, Long> {

    List<MyHomeAnnouncementMappingFailure> findAllByOrderBySourceKeyAsc();
}

package com.toadzip.backend.ingest.mapping.repository;

import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailure;
import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
import com.toadzip.backend.ingest.failure.repository.IngestFailureRepository;
import java.util.List;

public interface MyHomeAnnouncementMappingFailureRepository
        extends IngestFailureRepository<MyHomeAnnouncementMappingFailure> {

    List<MyHomeAnnouncementMappingFailure> findAllByStatus(IngestFailureStatus status);

    List<MyHomeAnnouncementMappingFailure> findAllBySourceAnnouncementIdentifier(String identifier);
}

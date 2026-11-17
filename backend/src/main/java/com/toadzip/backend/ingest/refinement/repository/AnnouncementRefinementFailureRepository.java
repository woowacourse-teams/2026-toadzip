package com.toadzip.backend.ingest.refinement.repository;

import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
import com.toadzip.backend.ingest.failure.repository.IngestFailureRepository;
import com.toadzip.backend.ingest.refinement.domain.AnnouncementRefinementFailure;
import java.util.List;

public interface AnnouncementRefinementFailureRepository
        extends IngestFailureRepository<AnnouncementRefinementFailure> {

    List<AnnouncementRefinementFailure> findAllByProviderAndStatus(AgencyCode provider, IngestFailureStatus status);
}

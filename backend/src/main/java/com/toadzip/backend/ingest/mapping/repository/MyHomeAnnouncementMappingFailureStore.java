package com.toadzip.backend.ingest.mapping.repository;

import com.toadzip.backend.ingest.failure.repository.IngestFailureStore;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailure;
import java.time.Clock;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyHomeAnnouncementMappingFailureStore extends IngestFailureStore<MyHomeAnnouncementMappingFailure> {

    private final MyHomeAnnouncementMappingFailureRepository repository;

    public MyHomeAnnouncementMappingFailureStore(MyHomeAnnouncementMappingFailureRepository repository, Clock clock) {
        super(repository, clock);
        this.repository = repository;
    }

    @Transactional
    public void resolveByAdmin(String identifier, String actor) {
        resolvePendingByAdmin(repository.findAllBySourceAnnouncementIdentifier(identifier), actor);
    }
}

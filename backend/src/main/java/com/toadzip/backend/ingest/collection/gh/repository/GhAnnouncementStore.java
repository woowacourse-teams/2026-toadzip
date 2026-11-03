package com.toadzip.backend.ingest.collection.gh.repository;

import com.toadzip.backend.ingest.collection.gh.domain.GhAnnouncementSnapshot;
import com.toadzip.backend.ingest.collection.gh.domain.GhAnnouncementSource;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class GhAnnouncementStore {

    private final GhAnnouncementSourceRepository repository;
    private final Clock clock;

    @Transactional
    public void store(GhAnnouncementSnapshot snapshot) {
        Instant now = clock.instant();
        var existing = repository.findBySourceKey(snapshot.sourceKey());
        if (existing.isEmpty()) {
            repository.save(GhAnnouncementSource.from(snapshot, now));
            return;
        }
        existing.orElseThrow().updateFrom(snapshot, now);
    }
}

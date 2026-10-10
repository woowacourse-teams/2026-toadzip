package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.ShAnnouncementSnapshot;
import com.toadzip.backend.ingest.collection.domain.ShAnnouncementSource;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class ShAnnouncementStore {

    private final ShAnnouncementSourceRepository repository;
    private final Clock clock;

    @Transactional
    public void store(ShAnnouncementSnapshot snapshot) {
        Instant now = clock.instant();
        var existing = repository.findBySourceKey(snapshot.sourceKey());
        if (existing.isEmpty()) {
            repository.save(ShAnnouncementSource.from(snapshot, now));
            return;
        }
        existing.orElseThrow().updateFrom(snapshot, now);
    }
}

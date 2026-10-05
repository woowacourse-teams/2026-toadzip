package com.toadzip.backend.ingest.collection.lh.repository;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuerySource;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LhAnnouncementQuerySourceReader {

    private final LhAnnouncementQuerySourceRepository sources;

    public Optional<LhAnnouncementQuerySource> find(CollectionSource source, String panId, String requestHash) {
        return sources.findBySourceAndPanIdAndRequestHash(source, panId, requestHash);
    }
}

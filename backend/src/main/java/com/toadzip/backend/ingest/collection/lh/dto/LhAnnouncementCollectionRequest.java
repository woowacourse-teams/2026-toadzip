package com.toadzip.backend.ingest.collection.lh.dto;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.dto.SourceCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public record LhAnnouncementCollectionRequest(
        UUID executionId, CollectionSource source, LhAnnouncementQuery query, int collectionVersion, Instant startedAt
) implements SourceCollectionRequest {

    public LhAnnouncementCollectionRequest {
        if (source != CollectionSource.LH_ANNOUNCEMENT_SUPPLY && source != CollectionSource.LH_ANNOUNCEMENT_DETAIL
                || query == null || collectionVersion < 1 || startedAt == null) {
            throw new IllegalArgumentException("LH 공고 수집 조건이 올바르지 않습니다.");
        }
        startedAt = startedAt.truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public Map<String, String> parameters() {
        var parameters = new HashMap<>(query.parameters());
        parameters.put("COLLECTION_VERSION", Integer.toString(collectionVersion));
        return Map.copyOf(parameters);
    }

    public String description() {
        return query.description() + "&COLLECTION_VERSION=" + collectionVersion;
    }

    public String requestHash() {
        return LhAnnouncementQuery.requestHashOf(description());
    }
}

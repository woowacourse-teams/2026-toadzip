package com.toadzip.backend.ingest.collection.lh.announcementcatalog.dto;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.dto.SourceCollectionRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

public record LhAnnouncementCatalogCollectionRequest(
        UUID executionId, int pageSize, int maxPages, Instant startedAt
) implements SourceCollectionRequest {

    public LhAnnouncementCatalogCollectionRequest {
        if (pageSize < 1 || pageSize > 9_999 || maxPages < 1 || maxPages > 1_000 || startedAt == null) {
            throw new IllegalArgumentException("LH 공고 목록의 수집 조건이 올바르지 않습니다.");
        }
        startedAt = startedAt.truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public CollectionSource source() {
        return CollectionSource.LH_ANNOUNCEMENT_CATALOG;
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("PG_SZ", Integer.toString(pageSize), "maxPages", Integer.toString(maxPages));
    }
}

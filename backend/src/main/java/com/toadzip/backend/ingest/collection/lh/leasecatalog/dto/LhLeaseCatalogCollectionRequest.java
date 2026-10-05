package com.toadzip.backend.ingest.collection.lh.leasecatalog.dto;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.dto.SourceCollectionRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record LhLeaseCatalogCollectionRequest(
        UUID executionId, int pageSize, int maxPages, Instant startedAt
) implements SourceCollectionRequest {

    public LhLeaseCatalogCollectionRequest {
        if (pageSize < 1 || pageSize > 9999 || maxPages < 1 || maxPages > 1000) {
            throw new IllegalArgumentException("페이지 크기는 1~9,999, 최대 페이지 수는 1~1,000이어야 합니다.");
        }
        startedAt = Objects.requireNonNull(startedAt, "요청 시작 시각은 필수입니다.").truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public CollectionSource source() {
        return CollectionSource.LH_LEASE_CATALOG;
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("scope", "ALL", "PG_SZ", Integer.toString(pageSize), "maxPages", Integer.toString(maxPages));
    }
}

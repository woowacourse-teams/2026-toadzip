package com.toadzip.backend.ingest.collection.lh.leasecatalog.dto;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.paging.dto.PagedSourceRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record LhLeaseCatalogCollectionRequest(
        UUID executionId, int pageSize, int maxPages, Instant startedAt
) implements PagedSourceRequest {

    public static final int MAX_PAGE_SIZE = 9_999;
    public static final int MAX_PAGES = 1_000;

    public LhLeaseCatalogCollectionRequest {
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE || maxPages < 1 || maxPages > MAX_PAGES) {
            throw new IllegalArgumentException(
                    "페이지 크기는 1~" + MAX_PAGE_SIZE + ", 최대 페이지 수는 1~" + MAX_PAGES + "이어야 합니다.");
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

    @Override
    public String pageDescription(int page) {
        return "PG_SZ=" + pageSize + "&PAGE=" + page;
    }

    /** 페이지 크기와 관계없이 이전 실패 기록을 모두 해소한다. */
    @Override
    public String resolvedFailurePrefix() {
        return "PG_SZ=";
    }
}

package com.toadzip.backend.ingest.collection.lh.announcementcatalog.dto;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.paging.dto.PagedSourceRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

public record LhAnnouncementCatalogCollectionRequest(
        UUID executionId, int pageSize, int maxPages, LocalDate startDate, LocalDate endDate, Instant startedAt
) implements PagedSourceRequest {

    public LhAnnouncementCatalogCollectionRequest {
        if (pageSize < 1 || pageSize > 9_999 || maxPages < 1 || maxPages > 1_000 || startedAt == null
                || startDate == null || endDate == null || startDate.isAfter(endDate)) {
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
        return Map.of("PG_SZ", Integer.toString(pageSize), "maxPages", Integer.toString(maxPages),
                "PAN_ST_DT", startDateParameter(), "PAN_ED_DT", endDateParameter());
    }

    /** LH 공고목록 API의 검색 시작일(공고게시일) 형식이다. 응답의 검색 조건도 같은 형식으로 돌아온다. */
    public String startDateParameter() {
        return DateTimeFormatter.BASIC_ISO_DATE.format(startDate);
    }

    public String endDateParameter() {
        return DateTimeFormatter.BASIC_ISO_DATE.format(endDate);
    }

    @Override
    public String pageDescription(int page) {
        return "PG_SZ=" + pageSize + "&PAGE=" + page;
    }

    @Override
    public String failureDescription() {
        return resolvedFailurePrefix();
    }

    @Override
    public String resolvedFailurePrefix() {
        return "PG_SZ=" + pageSize;
    }
}

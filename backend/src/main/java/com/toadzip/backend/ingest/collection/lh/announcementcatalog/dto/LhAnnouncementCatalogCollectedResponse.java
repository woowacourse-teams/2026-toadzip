package com.toadzip.backend.ingest.collection.lh.announcementcatalog.dto;

import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record LhAnnouncementCatalogCollectedResponse(
        int totalCount, String startDate, String endDate, Instant collectedAt, List<LhAnnouncementCatalogRow> rows
) {

    public LhAnnouncementCatalogCollectedResponse {
        if (collectedAt == null || startDate == null || startDate.isBlank() || endDate == null || endDate.isBlank()) {
            throw new IllegalArgumentException("LH 공고 목록의 검색 기간 또는 수집 시각이 비어 있습니다.");
        }
        LocalDate start = LocalDate.parse(startDate, DateTimeFormatter.BASIC_ISO_DATE);
        LocalDate end = LocalDate.parse(endDate, DateTimeFormatter.BASIC_ISO_DATE);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("LH 공고 목록의 검색 기간이 역전됐습니다.");
        }
        rows = List.copyOf(rows);
        collectedAt = collectedAt.truncatedTo(ChronoUnit.MICROS);
    }

    public void validateFor(LhAnnouncementCatalogCollectionRequest request) {
        if (totalCount <= 0 || totalCount != rows.size() || totalCount > (long) request.pageSize() * request.maxPages()
                || collectedAt.isBefore(request.startedAt())) {
            throw new IllegalArgumentException("완료되지 않은 LH 공고 목록은 저장할 수 없습니다.");
        }
        Set<String> keys = new HashSet<>();
        for (LhAnnouncementCatalogRow row : rows) {
            row.snapshot().validateIdentifiers();
            if (!keys.add(row.snapshot().sourceKey())) {
                throw new IllegalArgumentException("LH 공고 목록에 중복 공고 식별자가 있습니다.");
            }
        }
    }
}

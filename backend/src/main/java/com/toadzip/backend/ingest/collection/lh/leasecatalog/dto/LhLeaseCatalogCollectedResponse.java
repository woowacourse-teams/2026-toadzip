package com.toadzip.backend.ingest.collection.lh.leasecatalog.dto;

import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSourceSnapshot;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

public record LhLeaseCatalogCollectedResponse(int totalCount, Instant collectedAt, List<LhCatalogSourceSnapshot> rows) {

    public LhLeaseCatalogCollectedResponse {
        rows = List.copyOf(rows);
        if (collectedAt != null) {
            collectedAt = collectedAt.truncatedTo(ChronoUnit.MICROS);
        }
    }

    public void validateFor(LhLeaseCatalogCollectionRequest request) {
        if (collectedAt == null || collectedAt.isBefore(request.startedAt())) {
            throw new IllegalArgumentException("실제 수집 시각은 요청 시작 시각 이후여야 합니다.");
        }
        if (totalCount < 0 || totalCount != rows.size()
                || totalCount > (long) request.pageSize() * request.maxPages()) {
            throw new IllegalArgumentException("LH 임대 카탈로그 전체 응답 건수와 수집 범위가 일치하지 않습니다.");
        }
        rows.forEach(LhCatalogSourceSnapshot::validateIdentifiers);
    }
}

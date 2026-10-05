package com.toadzip.backend.ingest.collection.myhome.announcement.dto;

import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

public record MyHomeAnnouncementCollectedResponse(
        int totalCount,
        Instant collectedAt,
        List<MyHomeAnnouncementSourceSnapshot> rows
) {
    public MyHomeAnnouncementCollectedResponse {
        rows = List.copyOf(rows);
        if (collectedAt != null) {
            collectedAt = collectedAt.truncatedTo(ChronoUnit.MICROS);
        }
    }

    public void validateFor(MyHomeAnnouncementCollectionRequest request) {
        if (collectedAt == null || collectedAt.isBefore(request.startedAt())) {
            throw new IllegalArgumentException("실제 수집 시각은 요청 시작 시각 이후여야 합니다.");
        }
        if (totalCount < 0 || totalCount != rows.size()) {
            throw new IllegalArgumentException("전체 응답 건수와 수집한 행 수가 일치하지 않습니다.");
        }
        if (totalCount > (long) request.pageSize() * request.maxPages()) {
            throw new IllegalArgumentException("요청한 최대 페이지 범위 안에서 수집을 완료하지 못했습니다.");
        }
        rows.forEach(MyHomeAnnouncementSourceSnapshot::validateIdentifiers);
    }
}

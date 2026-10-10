package com.toadzip.backend.ingest.collection.myhome.announcement.dto;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.dto.SourceCollectionRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record MyHomeAnnouncementCollectionRequest(
        UUID executionId,
        String supplyTypeCode,
        int pageSize,
        int maxPages,
        Instant startedAt,
        String pblancId
) implements SourceCollectionRequest {
    public MyHomeAnnouncementCollectionRequest(
            UUID executionId, String supplyTypeCode, int pageSize, int maxPages, Instant startedAt
    ) {
        this(executionId, supplyTypeCode, pageSize, maxPages, startedAt, null);
    }

    public MyHomeAnnouncementCollectionRequest {
        if (supplyTypeCode == null || !supplyTypeCode.matches("[0-9]{2}")) {
            throw new IllegalArgumentException("공급유형 코드는 두 자리 숫자여야 합니다.");
        }
        if (pageSize <= 0 || pageSize > 1000 || maxPages <= 0 || maxPages > 1000) {
            throw new IllegalArgumentException("페이지 크기와 최대 페이지 수는 1~1,000이어야 합니다.");
        }
        startedAt = Objects.requireNonNull(startedAt, "요청 시작 시각은 필수입니다.").truncatedTo(ChronoUnit.MICROS);
        if (pblancId != null && (pblancId.isBlank() || pblancId.length() > 100)) {
            throw new IllegalArgumentException("공고 식별자는 1자 이상 100자 이하여야 합니다.");
        }
    }

    @Override
    public CollectionSource source() {
        return CollectionSource.MYHOME_ANNOUNCEMENT;
    }

    @Override
    public Map<String, String> parameters() {
        var parameters = new java.util.LinkedHashMap<>(Map.of(
                "suplyTy", supplyTypeCode,
                "numOfRows", Integer.toString(pageSize),
                "maxPages", Integer.toString(maxPages)
        ));
        if (pblancId != null) {
            parameters.put("pblancId", pblancId);
        }
        return Map.copyOf(parameters);
    }
}

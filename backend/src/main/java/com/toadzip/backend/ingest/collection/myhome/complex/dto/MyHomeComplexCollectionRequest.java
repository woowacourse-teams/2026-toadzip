package com.toadzip.backend.ingest.collection.myhome.complex.dto;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.dto.SourceCollectionRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record MyHomeComplexCollectionRequest(
        UUID executionId,
        String provinceCode,
        String districtCode,
        int pageSize,
        int maxPages,
        Instant startedAt
) implements SourceCollectionRequest {
    public MyHomeComplexCollectionRequest {
        if (provinceCode == null || !provinceCode.matches("[0-9]{2}")
                || districtCode == null || !districtCode.matches("[0-9]{3}")) {
            throw new IllegalArgumentException("시도 두 자리·시군구 세 자리 코드는 함께 입력해야 합니다.");
        }
        if (pageSize <= 0 || pageSize > 1000 || maxPages <= 0 || maxPages > 1000) {
            throw new IllegalArgumentException("페이지 크기와 최대 페이지 수는 1~1,000이어야 합니다.");
        }
        startedAt = Objects.requireNonNull(startedAt, "요청 시작 시각은 필수입니다.").truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public CollectionSource source() {
        return CollectionSource.MYHOME_COMPLEX;
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of(
                "brtcCode", provinceCode,
                "signguCode", districtCode,
                "numOfRows", Integer.toString(pageSize),
                "maxPages", Integer.toString(maxPages)
        );
    }
}

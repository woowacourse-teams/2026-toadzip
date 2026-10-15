package com.toadzip.backend.housing.dto.response;

import com.toadzip.backend.housing.domain.ComplexComparisonSourceType;
import com.toadzip.backend.housing.dto.request.AdminHousingComplexUpdateRequest;
import com.toadzip.backend.ingest.source.dto.IngestSourcePageResponse.Item;
import java.time.Instant;
import java.util.List;

public record ComplexComparisonResponse(String snapshotToken, AdminHousingComplexUpdateRequest current,
        List<Source> sources) {
    public record Source(ComplexComparisonSourceType category, String sourceKey, boolean automatic,
            String evidenceNote, String actor, Instant linkedAt, Item data) { }
}

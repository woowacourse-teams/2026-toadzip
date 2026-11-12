package com.toadzip.backend.ingest.mapping.dto;

import com.toadzip.backend.ingest.domain.IngestStepReport;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus;
import java.util.Map;

public record InitialRegistrationRunResponse(Map<InitialRegistrationStatus, Long> counts,
        int targetCount, int uniqueAddressCount) implements IngestStepReport {

    @Override
    public int stepFailureCount() {
        return Math.toIntExact(counts.getOrDefault(InitialRegistrationStatus.FAILED, 0L));
    }

    @Override
    public int stepWarningCount() {
        return Math.toIntExact(counts.getOrDefault(InitialRegistrationStatus.INFORMATION_REQUIRED, 0L)
                + counts.getOrDefault(InitialRegistrationStatus.LOCATION_REQUIRED, 0L));
    }
}

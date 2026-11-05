package com.toadzip.backend.ingest.mapping.dto;

import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationTarget;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record InitialRegistrationResponse(Map<InitialRegistrationStatus, Long> counts, List<Target> targets,
                                          long totalElements, int page, int totalPages) {

    public record Target(String sourceComplexIdentifier, String name, String roadAddress,
                         InitialRegistrationStatus status, String detail, Long housingComplexId, Instant checkedAt) {

        public static Target from(InitialRegistrationTarget target) {
            return new Target(target.getSourceComplexIdentifier(), target.getName(), target.getRoadAddress(),
                    target.getStatus(), target.getDetail(), target.getHousingComplexId(), target.getCheckedAt());
        }
    }
}

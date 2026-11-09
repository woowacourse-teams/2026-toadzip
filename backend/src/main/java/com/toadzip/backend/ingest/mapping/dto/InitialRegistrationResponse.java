package com.toadzip.backend.ingest.mapping.dto;

import com.toadzip.backend.ingest.location.domain.RoadAddressGeocodingFailureReason;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationLocation;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationTarget;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record InitialRegistrationResponse(Map<InitialRegistrationStatus, Long> counts, List<Target> targets,
                                          long totalElements, int page, int totalPages) {

    public record Target(String sourceComplexIdentifier, String name, String roadAddress,
                         InitialRegistrationStatus status, String detail, Long housingComplexId, Instant checkedAt,
                         Location location) {

        public static Target from(InitialRegistrationTarget target) {
            return new Target(target.getSourceComplexIdentifier(), target.getName(), target.getRoadAddress(),
                    target.getStatus(), target.getDetail(), target.getHousingComplexId(), target.getCheckedAt(),
                    Location.from(target.getLocation()));
        }
    }

    public record Location(String address, BigDecimal latitude, BigDecimal longitude, String origin,
                           RoadAddressGeocodingFailureReason failure, Instant checkedAt, String checkedBy) {
        static Location from(InitialRegistrationLocation location) {
            if (location == null) {
                return null;
            }
            return new Location(location.getAddress(), location.getLatitude(), location.getLongitude(),
                    location.getOrigin(), location.getFailure(), location.getCheckedAt(), location.getCheckedBy());
        }
    }
}

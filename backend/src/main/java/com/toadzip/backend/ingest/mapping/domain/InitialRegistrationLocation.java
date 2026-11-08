package com.toadzip.backend.ingest.mapping.domain;

import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.ingest.location.domain.NormalizedRoadAddress;
import com.toadzip.backend.ingest.location.domain.RoadAddressGeocodingFailureReason;
import com.toadzip.backend.ingest.location.domain.Wgs84Coordinate;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Embeddable
@NoArgsConstructor(access = PROTECTED)
public class InitialRegistrationLocation {

    @Column(name = "location_source_address", columnDefinition = "text")
    private String sourceAddress;
    @Column(name = "prepared_address", columnDefinition = "text")
    private String address;
    @Column(name = "prepared_latitude", precision = 10, scale = 8)
    private BigDecimal latitude;
    @Column(name = "prepared_longitude", precision = 11, scale = 8)
    private BigDecimal longitude;
    @Column(name = "location_origin", length = 10)
    private String origin;
    @Enumerated(EnumType.STRING)
    @Column(name = "location_failure", length = 40)
    private RoadAddressGeocodingFailureReason failure;
    @Column(name = "location_checked_at")
    private Instant checkedAt;
    @Column(name = "location_checked_by", length = 255)
    private String checkedBy;

    public static InitialRegistrationLocation prepared(String sourceAddress, String address,
            BigDecimal latitude, BigDecimal longitude, boolean administrator, String actor, Instant now) {
        var normalized = new NormalizedRoadAddress(address);
        var coordinate = new Wgs84Coordinate(latitude, longitude);
        InitialRegistrationLocation result = new InitialRegistrationLocation();
        result.sourceAddress = sourceAddress;
        result.address = normalized.value();
        result.latitude = coordinate.latitude();
        result.longitude = coordinate.longitude();
        result.origin = "ZIP";
        if (administrator) {
            result.origin = "ADMIN";
        }
        result.checkedAt = now;
        result.checkedBy = actor;
        return result;
    }

    public static InitialRegistrationLocation failed(String sourceAddress,
            RoadAddressGeocodingFailureReason failure, Instant now) {
        InitialRegistrationLocation result = new InitialRegistrationLocation();
        result.sourceAddress = sourceAddress;
        result.failure = Objects.requireNonNull(failure);
        result.checkedAt = now;
        return result;
    }

    public boolean isReady() {
        return address != null && latitude != null && longitude != null && failure == null;
    }

    public boolean isAdministratorValueFor(String sourceAddress) {
        return isReady() && "ADMIN".equals(origin) && Objects.equals(this.sourceAddress, sourceAddress);
    }
}

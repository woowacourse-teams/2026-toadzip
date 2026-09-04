package com.toadzip.backend.ingest.domain;

import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.ingest.dto.GeocodedRoadAddress;
import com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingFailureReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "myhome_complex_mapping_candidates",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_myhome_mapping_candidate_source_identifier",
                columnNames = "source_complex_identifier"
        )
)
@NoArgsConstructor(access = PROTECTED)
public class MyHomeComplexMappingCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sourceComplexIdentifier;

    @Column(nullable = false, length = 500)
    private String sourceRoadAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MyHomeComplexMappingCandidateStatus status;

    @Column(length = 500)
    private String resolvedRoadAddress;

    @Column(precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(precision = 10, scale = 6)
    private BigDecimal longitude;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private RoadAddressGeocodingFailureReason geocodingFailureReason;

    private MyHomeComplexMappingCandidate(String sourceComplexIdentifier, String sourceRoadAddress) {
        validateNotBlank(sourceComplexIdentifier, "원천 단지 식별자");
        validateNotBlank(sourceRoadAddress, "원천 도로명주소");
        this.sourceComplexIdentifier = sourceComplexIdentifier;
        this.sourceRoadAddress = sourceRoadAddress;
        status = MyHomeComplexMappingCandidateStatus.PENDING;
    }

    public static MyHomeComplexMappingCandidate pending(
            String sourceComplexIdentifier,
            String sourceRoadAddress
    ) {
        return new MyHomeComplexMappingCandidate(sourceComplexIdentifier, sourceRoadAddress);
    }

    public void prepare(String sourceRoadAddress) {
        validateNotBlank(sourceRoadAddress, "원천 도로명주소");
        if (!this.sourceRoadAddress.equals(sourceRoadAddress)) {
            resetAddress(sourceRoadAddress);
            return;
        }
        if (hasCoordinates()) {
            status = MyHomeComplexMappingCandidateStatus.GEOCODED;
            return;
        }
        status = MyHomeComplexMappingCandidateStatus.PENDING;
        geocodingFailureReason = null;
    }

    public void resolve(GeocodedRoadAddress address) {
        if (address == null
                || address.roadAddress() == null
                || address.roadAddress().isBlank()
                || address.latitude() == null
                || address.longitude() == null) {
            throw new IllegalArgumentException("좌표 변환 결과는 필수입니다.");
        }
        resolvedRoadAddress = address.roadAddress();
        latitude = address.latitude();
        longitude = address.longitude();
        geocodingFailureReason = null;
        status = MyHomeComplexMappingCandidateStatus.GEOCODED;
    }

    public void failGeocoding(RoadAddressGeocodingFailureReason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("좌표 변환 실패 사유는 필수입니다.");
        }
        resolvedRoadAddress = null;
        latitude = null;
        longitude = null;
        geocodingFailureReason = reason;
        status = MyHomeComplexMappingCandidateStatus.GEOCODING_FAILED;
    }

    public void markMapped() {
        if (!hasCoordinates()) {
            throw new IllegalStateException("좌표가 확정된 후보만 최종 적재할 수 있습니다.");
        }
        status = MyHomeComplexMappingCandidateStatus.MAPPED;
    }

    public void failMapping() {
        if (!hasCoordinates()) {
            throw new IllegalStateException("좌표가 확정된 후보만 최종 적재 실패로 기록할 수 있습니다.");
        }
        status = MyHomeComplexMappingCandidateStatus.MAPPING_FAILED;
    }

    public boolean needsGeocoding() {
        return !hasCoordinates();
    }

    public GeocodedRoadAddress geocodedAddress() {
        if (!hasCoordinates()) {
            throw new IllegalStateException("좌표가 확정되지 않았습니다.");
        }
        return new GeocodedRoadAddress(resolvedRoadAddress, latitude, longitude);
    }

    private boolean hasCoordinates() {
        return resolvedRoadAddress != null && latitude != null && longitude != null;
    }

    private void resetAddress(String sourceRoadAddress) {
        this.sourceRoadAddress = sourceRoadAddress;
        resolvedRoadAddress = null;
        latitude = null;
        longitude = null;
        geocodingFailureReason = null;
        status = MyHomeComplexMappingCandidateStatus.PENDING;
    }

    private static void validateNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 필수입니다.");
        }
    }
}

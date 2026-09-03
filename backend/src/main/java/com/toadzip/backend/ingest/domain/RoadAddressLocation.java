package com.toadzip.backend.ingest.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "road_address_locations",
        indexes = @Index(name = "idx_road_address_location_address", columnList = "normalized_road_address")
)
@NoArgsConstructor(access = PROTECTED)
public class RoadAddressLocation {

    @EmbeddedId
    private RoadAddressLocationId id;

    @Column(name = "province_code", nullable = false, length = 2)
    private String provinceCode;

    @Column(name = "road_address", nullable = false, length = 500)
    private String roadAddress;

    @Column(name = "normalized_road_address", nullable = false, length = 500)
    private String normalizedRoadAddress;

    @Column(precision = 15, scale = 6)
    private BigDecimal x;

    @Column(precision = 15, scale = 6)
    private BigDecimal y;

    private RoadAddressLocation(LocationSummaryRecord record) {
        id = new RoadAddressLocationId(record);
        provinceCode = record.provinceCode();
        roadAddress = record.roadAddress();
        normalizedRoadAddress = record.normalizedRoadAddress();
        x = record.x();
        y = record.y();
    }

    public static RoadAddressLocation from(LocationSummaryRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("위치정보요약DB 레코드는 필수입니다.");
        }
        return new RoadAddressLocation(record);
    }

    public Optional<UtmKCoordinate> coordinate() {
        if (x == null || y == null) {
            return Optional.empty();
        }
        return Optional.of(new UtmKCoordinate(x, y));
    }
}

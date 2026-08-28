package com.toadzip.backend.housing.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Embeddable
@NoArgsConstructor(access = PROTECTED)
public class Address {

    private static final int COORDINATE_SCALE = 6;

    @Column(nullable = false)
    private String roadAddress;

    @Column(nullable = false)
    private String pnu;

    @Column(nullable = false)
    private String legalDongCode;

    @Column(nullable = false)
    private String provinceCode;

    @Column(nullable = false)
    private String cityCountyDistrictCode;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal longitude;

    private Address(
            String roadAddress,
            String pnu,
            String legalDongCode,
            String provinceCode,
            String cityCountyDistrictCode,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
        validateNotBlank(roadAddress, "도로명주소");
        validateNotBlank(pnu, "PNU");
        validateNotBlank(legalDongCode, "법정동코드");
        validateNotBlank(provinceCode, "시·도 코드");
        validateNotBlank(cityCountyDistrictCode, "시·군·구 코드");
        this.roadAddress = roadAddress;
        this.pnu = pnu;
        this.legalDongCode = legalDongCode;
        this.provinceCode = provinceCode;
        this.cityCountyDistrictCode = cityCountyDistrictCode;
        this.latitude = normalizeCoordinate(latitude);
        this.longitude = normalizeCoordinate(longitude);
    }

    public static Address create(
            String roadAddress,
            String pnu,
            String legalDongCode,
            String provinceCode,
            String cityCountyDistrictCode,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
        validateLatitude(latitude);
        validateLongitude(longitude);
        return new Address(
                roadAddress,
                pnu,
                legalDongCode,
                provinceCode,
                cityCountyDistrictCode,
                latitude,
                longitude
        );
    }

    public boolean hasSameValues(Address other) {
        if (other == null) {
            return false;
        }
        return roadAddress.equals(other.roadAddress)
                && pnu.equals(other.pnu)
                && legalDongCode.equals(other.legalDongCode)
                && provinceCode.equals(other.provinceCode)
                && cityCountyDistrictCode.equals(other.cityCountyDistrictCode)
                && Objects.equals(latitude, other.latitude)
                && Objects.equals(longitude, other.longitude);
    }

    private void validateNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
    }

    private static void validateRequired(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
    }

    private static void validateLatitude(BigDecimal latitude) {
        validateRequired(latitude, "위도");
        if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new IllegalArgumentException("위도는 -90도 이상 90도 이하여야 한다.");
        }
    }

    private static void validateLongitude(BigDecimal longitude) {
        validateRequired(longitude, "경도");
        if (longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalArgumentException("경도는 -180도 이상 180도 이하여야 한다.");
        }
    }

    private static BigDecimal normalizeCoordinate(BigDecimal coordinate) {
        return coordinate.setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
    }
}

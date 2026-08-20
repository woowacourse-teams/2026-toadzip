package com.toadzip.backend.housing.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Embeddable
@NoArgsConstructor(access = PROTECTED)
public class Address {

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

    @Column(nullable = false)
    private BigDecimal latitude;

    @Column(nullable = false)
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
        validateRequired(latitude, "위도");
        validateRequired(longitude, "경도");
        this.roadAddress = roadAddress;
        this.pnu = pnu;
        this.legalDongCode = legalDongCode;
        this.provinceCode = provinceCode;
        this.cityCountyDistrictCode = cityCountyDistrictCode;
        this.latitude = latitude;
        this.longitude = longitude;
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

    private void validateNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
    }

    private void validateRequired(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
    }
}
